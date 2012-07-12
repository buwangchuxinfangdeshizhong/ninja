package ninja;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import ninja.async.AsyncStrategy;
import ninja.async.AsyncStrategyFactoryHolder;
import ninja.bodyparser.BodyParserEngine;
import ninja.bodyparser.BodyParserEngineManager;
import ninja.session.FlashCookie;
import ninja.session.SessionCookie;
import ninja.utils.CookieHelper;
import ninja.utils.NinjaConstant;

import com.google.inject.Inject;

public class ContextImpl implements Context {

	private HttpServletRequest httpServletRequest;

	private HttpServletResponse httpServletResponse;

	private Route route;

	// * if set this template is used. otherwise the default mapping **/
	private String templateOverride = null;

	public String contentType;

	private AsyncStrategy asyncStrategy;
	private final Object asyncLock = new Object();

	private final BodyParserEngineManager bodyParserEngineManager;

	private final FlashCookie flashCookie;

	private final SessionCookie sessionCookie;

	@Inject
	public ContextImpl(BodyParserEngineManager bodyParserEngineManager,
			FlashCookie flashCookie, SessionCookie sessionCookie) {

		this.bodyParserEngineManager = bodyParserEngineManager;
		this.flashCookie = flashCookie;
		this.sessionCookie = sessionCookie;
	}

	public void init(HttpServletRequest httpServletRequest,
			HttpServletResponse httpServletResponse) {
		this.httpServletRequest = httpServletRequest;
		this.httpServletResponse = httpServletResponse;

		// init flash scope:
		flashCookie.init(this);

		// init session scope:
		sessionCookie.init(this);

	}

	public void setRoute(Route route) {
		this.route = route;
	}

	public HttpServletRequest getHttpServletRequest() {
		return httpServletRequest;
	}

	public HttpServletResponse getHttpServletResponse() {
		return this.httpServletResponse;
	}

	@Override
	public String getPathParameter(String key) {
		return route.getParameters(httpServletRequest.getServletPath())
				.get(key);
	}

	@Override
	public Integer getPathParameterAsInteger(String key) {
		String parameter = getPathParameter(key);

		try {
			return Integer.parseInt(parameter);
		} catch (Exception e) {
			return null;
		}
	}

	@Override
	public String getParameter(String key) {
		return httpServletRequest.getParameter(key);
	}

	@Override
	public String getParameter(String key, String defaultValue) {
		String parameter = getParameter(key);

		if (parameter == null) {
			parameter = defaultValue;
		}

		return parameter;
	}

	@Override
	public Integer getParameterAsInteger(String key) {
		return getParameterAsInteger(key, null);
	}

	@Override
	public Integer getParameterAsInteger(String key, Integer defaultValue) {
		String parameter = getParameter(key);

		try {
			return Integer.parseInt(parameter);
		} catch (Exception e) {
			return defaultValue;
		}
	}

	@Override
	public Map<String, String[]> getParameters() {
		return httpServletRequest.getParameterMap();
	}

	@Override
	public String getHeader(String name) {
		return httpServletRequest.getHeader(name);
	}

	@Override
	public Map<String, String> getHeaders() {
		Map<String, String> headers = new HashMap<String, String>();
		Enumeration<String> enumeration = httpServletRequest.getHeaderNames();
		while (enumeration.hasMoreElements()) {
			String name = enumeration.nextElement();
			headers.put(name, httpServletRequest.getHeader(name));
		}
		return headers;
	}

	@Override
	public String getCookieValue(String name) {
		return CookieHelper.getCookieValue(name,
				httpServletRequest.getCookies());
	}

	@Override
	public String getTemplateName(String suffix) {
		if (templateOverride == null) {
			Class controller = route.getControllerClass();

			// Calculate the correct path of the template.
			// We always assume the template in the subdir "views"

			// 1) If we are in the main project =>
			// /views/ControllerName/templateName.ftl.html
			// 2) If we are in a plugin / subproject
			// =>
			// some/packages/submoduleName/views/ControllerName/templateName.ftl.html

			// So let's calculate the parent package of the controller:
			String controllerPackageName = controller.getPackage().getName();
			// This results in something like controllers or
			// some.package.controllers

			// Let's remove "controllers" so we cat all parent packages:
			String parentPackageOfController = controllerPackageName
					.replaceAll(NinjaConstant.CONTROLLERS_DIR, "");

			// And now we rewrite everything from "." notation to directories /
			String parentControllerPackageAsPath = parentPackageOfController
					.replaceAll("\\.", "/");

			// and the final path of the controller will be something like:
			// some/package/views/ControllerName/templateName.ftl.html
			return String.format("%sviews/%s/%s%s",
					parentControllerPackageAsPath, controller.getSimpleName(),
					route.getControllerMethod().getName(), suffix);
		}
		return templateOverride;
	}

	@Override
	public <T> T parseBody(Class<T> classOfT) {

		BodyParserEngine bodyParserEngine = bodyParserEngineManager
				.getBodyParserEngineForContentType(ContentTypes.APPLICATION_JSON);

		if (bodyParserEngine == null) {
			return null;
		}

		return bodyParserEngine.invoke(this, classOfT);

	}

	@Override
	public FlashCookie getFlashCookie() {
		return flashCookie;
	}

	@Override
	public SessionCookie getSessionCookie() {
		return sessionCookie;
	}

	@Override
	public String getRequestUri() {
		return getHttpServletRequest().getRequestURI();
	}

	@Override
	public void handleAsync() {
		synchronized (asyncLock) {
			if (asyncStrategy == null) {
				asyncStrategy = AsyncStrategyFactoryHolder.INSTANCE
						.createStrategy(httpServletRequest);
				asyncStrategy.handleAsync();
			}
		}
	}

	@Override
	public void returnResultAsync(Result result) {
		synchronized (asyncLock) {
			handleAsync();
			asyncStrategy.returnResultAsync(result);
		}
	}

	/**
	 * Used to indicate that the controller has finished executing
	 */
	public Result controllerReturned() {
		synchronized (asyncLock) {
			if (asyncStrategy != null) {
				return asyncStrategy.controllerReturned();
			}
		}
		return null;
	}

	@Override
	public InputStream getInputStream() throws IOException {
		return httpServletRequest.getInputStream();
	}

	@Override
	public BufferedReader getReader() throws IOException {
		return httpServletRequest.getReader();
	}

	@Override
	public OutputStream getOutputStream() throws IOException {
		return httpServletResponse.getOutputStream();
	}

	@Override
	public Writer getWriter() throws IOException {
		return httpServletResponse.getWriter();
	}

	@Override
	public void finalizeHeaders(Result result) {
		httpServletResponse.setContentType(contentType);
		httpServletResponse.setStatus(result.getStatusCode());

		flashCookie.save(this);
		sessionCookie.save(this);

		for (ninja.Cookie cookie : result.getCookies()) {
			httpServletResponse.addCookie(CookieHelper
					.convertNinjaCookieToServletCookie(cookie));

		}

	}

}
