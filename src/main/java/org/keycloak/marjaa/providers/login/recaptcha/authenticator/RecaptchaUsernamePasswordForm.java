package org.keycloak.marjaa.providers.login.recaptcha.authenticator;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.message.BasicNameValuePair;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.authenticators.browser.UsernamePasswordForm;
import org.keycloak.connections.httpclient.HttpClientProvider;
import org.keycloak.events.Details;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.utils.FormMessage;
import org.keycloak.services.ServicesLogger;
import org.keycloak.services.messages.Messages;
import org.keycloak.services.validation.Validation;
import org.keycloak.util.JsonSerialization;

import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class RecaptchaUsernamePasswordForm extends UsernamePasswordForm implements Authenticator{
	public static final String G_RECAPTCHA_RESPONSE = "g-recaptcha-response";
	public static final String RECAPTCHA_REFERENCE_CATEGORY = "recaptcha";
	public static final String SITE_KEY = "site.key";
	public static final String SITE_SECRET = "secret";
	private static final Logger logger = Logger.getLogger(RecaptchaUsernamePasswordFormFactory.class);

	protected Response challenge(AuthenticationFlowContext context, String error) {

		LoginFormsProvider form = context.form()
				.setExecution(context.getExecution().getId());

		if (error != null){
			form.setError(error);
		}

		Map<String, String> captchaConfig = getCaptchaConfig(context.getAuthenticatorConfig());
		if (captchaConfig == null) {
			form.addError(new FormMessage(null, Messages.RECAPTCHA_NOT_CONFIGURED));
		} else {
			String siteKey = captchaConfig.get(SITE_KEY);
			form.setAttribute("recaptchaRequired", true);
			form.setAttribute("recaptchaSiteKey", siteKey);
		}

		return createLoginForm(form);
	}

	@Override
	public void authenticate(AuthenticationFlowContext context) {
		context.getEvent().detail(Details.AUTH_METHOD, "auth_method");
		if (logger.isInfoEnabled()) {
			logger.info(
					"validateRecaptcha(AuthenticationFlowContext, boolean, String, String) - Before the validation");
		}

		String userLanguageTag = context.getSession().getContext().resolveLocale(context.getUser()).toLanguageTag();

		AuthenticatorConfigModel captchaConfigModel = context.getAuthenticatorConfig();
		Map<String, String> captchaConfig = getCaptchaConfig(captchaConfigModel);

		LoginFormsProvider form = context.form();
		if (captchaConfig == null) {
			form.addError(new FormMessage(null, Messages.RECAPTCHA_NOT_CONFIGURED));
			return;
		}

		String siteKey = captchaConfig.get(SITE_KEY);
		form.setAttribute("recaptchaRequired", true);
		form.setAttribute("recaptchaSiteKey", siteKey);
		form.addScript("https://www.google.com/recaptcha/api.js?hl=" + userLanguageTag);

		super.authenticate(context);
	}

	private Map<String, String> getCaptchaConfig(AuthenticatorConfigModel captchaConfigModel) {

		if (captchaConfigModel == null) {
			return null;
		}

		Map<String, String> config = captchaConfigModel.getConfig();

		if (config == null || config.get(SITE_KEY) == null || config.get(SITE_SECRET) == null) {
			return null;
		}

		return config;
	}

	@Override
	public void action(AuthenticationFlowContext context) {
		if (logger.isDebugEnabled()) {
			logger.debug("action(AuthenticationFlowContext) - start");
		}
		MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();
		List<FormMessage> errors = new ArrayList<>();
		boolean success = false;
		context.getEvent().detail(Details.AUTH_METHOD, "auth_method");

		String captcha = formData.getFirst(G_RECAPTCHA_RESPONSE);
		if (!Validation.isBlank(captcha)) {
			AuthenticatorConfigModel captchaConfig = context.getAuthenticatorConfig();
			String secret = captchaConfig.getConfig().get(SITE_SECRET);

			success = validateRecaptcha(context, success, captcha, secret);
		}
		if (success) {
			super.action(context);
		} else {
			errors.add(new FormMessage(null, Messages.RECAPTCHA_FAILED));
			formData.remove(G_RECAPTCHA_RESPONSE);
			// context.error(Errors.INVALID_REGISTRATION);
			// context.validationError(formData, errors);
			// context.excludeOtherErrors();
			return;
		}

		if (logger.isDebugEnabled()) {
			logger.debug("action(AuthenticationFlowContext) - end");
		}
	}

	protected boolean validateRecaptcha(AuthenticationFlowContext context, boolean success, String captcha, String secret) {
		HttpClient httpClient = context.getSession().getProvider(HttpClientProvider.class).getHttpClient();
		HttpPost post = new HttpPost("https://www.google.com/recaptcha/api/siteverify");
		List<NameValuePair> formparams = new LinkedList<>();
		formparams.add(new BasicNameValuePair("secret", secret));
		formparams.add(new BasicNameValuePair("response", captcha));
		formparams.add(new BasicNameValuePair("remoteip", context.getConnection().getRemoteAddr()));
		try {
			UrlEncodedFormEntity form = new UrlEncodedFormEntity(formparams, "UTF-8");
			post.setEntity(form);
			HttpResponse response = httpClient.execute(post);
			InputStream content = response.getEntity().getContent();
			try {
				Map json = JsonSerialization.readValue(content, Map.class);
				Object val = json.get("success");
				success = Boolean.TRUE.equals(val);
			} finally {
				content.close();
			}
		} catch (Exception e) {
			ServicesLogger.LOGGER.recaptchaFailed(e);
		}
		return success;
	}

}
