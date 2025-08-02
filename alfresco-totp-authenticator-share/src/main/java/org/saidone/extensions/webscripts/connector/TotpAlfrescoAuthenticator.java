package org.saidone.extensions.webscripts.connector;

import java.text.MessageFormat;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.JSONException;
import org.json.JSONObject;
import org.saidone.web.site.TotpSlingshotUserFactory;
import org.springframework.extensions.config.RemoteConfigElement;
import org.springframework.extensions.config.RemoteConfigElement.EndpointDescriptor;
import org.springframework.extensions.surf.exception.AuthenticationException;
import org.springframework.extensions.webscripts.Status;
import org.springframework.extensions.webscripts.connector.ConnectorSession;
import org.springframework.extensions.webscripts.connector.Credentials;
import org.springframework.extensions.webscripts.connector.RemoteClient;
import org.springframework.extensions.webscripts.connector.Response;
import org.springframework.extensions.webscripts.json.JSONWriter;

/**
 * {@link org.springframework.extensions.webscripts.connector.AlfrescoAuthenticator}
 * implementation that submits the TOTP token together with the username and
 * password when performing the login handshake.
 */
public class TotpAlfrescoAuthenticator extends org.springframework.extensions.webscripts.connector.AlfrescoAuthenticator {
    private static final Log logger = LogFactory.getLog(TotpAlfrescoAuthenticator.class);

    private static final String JSON_LOGIN = "'{'\"username\": \"{0}\", \"password\": \"{1}\", \"token\": \"{2}\"'}'";
    private static final String API_LOGIN = "/api/login";
    private static final String MIMETYPE_APPLICATION_JSON = "application/json";

    public final static String CS_PARAM_ALF_TICKET = "alfTicket";

    /**
     * Performs an authentication handshake including the TOTP token if present in
     * the credentials.
     *
     * @param endpoint         endpoint identifier
     * @param credentials      user credentials, possibly containing a token
     * @param connectorSession current connector session
     * @return the authenticated session or {@code null} if authentication fails
     * @throws AuthenticationException if authentication is not possible
     */
    public ConnectorSession authenticate(String endpoint, Credentials credentials, ConnectorSession connectorSession)
            throws AuthenticationException {
        ConnectorSession cs = null;

        String user, pass, token;
        if (credentials == null || (user = (String) credentials.getProperty(Credentials.CREDENTIAL_USERNAME)) == null ||
                (pass = (String) credentials.getProperty(Credentials.CREDENTIAL_PASSWORD)) == null) {
            logger.debug("No user credentials available - cannot authenticate.");
        } else {
            // build a new remote client

            // Take endpointId from credentials as the endpoint may be remapped to allow an endpoint to credentials from
            // a parent endpoint - for the purposes of authentication, we want to use the parent endpoint URL.
            // @see ConnectorService.getConnectorSession()
            // @see SimpleCredentialVault.retrieve()
            final String endpointId = credentials.getEndpointId();
            final RemoteConfigElement config = getConnectorService().getRemoteConfig();
            final EndpointDescriptor desc = config.getEndpointDescriptor(endpointId);
            if (desc == null) {
                throw new IllegalArgumentException("Unknown endpoint ID: " + endpointId);
            }
            final RemoteClient remoteClient = buildRemoteClient(config.getEndpointDescriptor(endpointId).getEndpointUrl());

            logger.debug("Authenticating user: " + user);

            // retrieve token if any
            token = (String) credentials.getProperty(TotpSlingshotUserFactory.CREDENTIAL_TOKEN);

            // POST to the Alfresco login WebScript
            remoteClient.setRequestContentType(MIMETYPE_APPLICATION_JSON);
            String body = MessageFormat.format(
                    JSON_LOGIN, JSONWriter.encodeJSONString(user),
                    JSONWriter.encodeJSONString(pass),
                    JSONWriter.encodeJSONString(token));
            Response response = remoteClient.call(getLoginURL(), body);

            // read back the ticket
            if (response.getStatus().getCode() == 200) {
                String ticket;
                try {
                    JSONObject json = new JSONObject(response.getResponse());
                    ticket = json.getJSONObject("data").getString("ticket");
                } catch (JSONException jErr) {
                    // the ticket that came back could not be parsed
                    // this will cause the entire handshake to fail
                    throw new AuthenticationException(
                            "Unable to retrieve login ticket from Alfresco", jErr);
                }
                logger.debug("Parsed ticket: " + ticket);

                // place the ticket back into the connector session
                if (connectorSession != null) {
                    connectorSession.setParameter(CS_PARAM_ALF_TICKET, ticket);

                    // signal that this succeeded
                    cs = connectorSession;
                }
            } else if (response.getStatus().getCode() == Status.STATUS_NO_CONTENT) {
                logger.debug("SC_NO_CONTENT(204) status received - retreiving auth cookies...");

                // The login created an empty response, probably with cookies in the connectorSession. We succeeded.
                processResponse(response, connectorSession);
                cs = connectorSession;
            } else {
                logger.debug("Authentication failed, received response code: " + response.getStatus().getCode());
            }
        }

        return cs;
    }

    /**
     * Returns the login URL used to authenticate against the repository.
     *
     * @return login URL
     */
    protected String getLoginURL() {
        return API_LOGIN;
    }
}