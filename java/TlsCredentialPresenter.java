package org.astrogrid.samp.tls;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.astrogrid.samp.client.SampException;
import org.astrogrid.samp.httpd.HttpServer;
import org.astrogrid.samp.web.AuthResourceBundle;
import org.astrogrid.samp.web.ClientAuthorizers;
import org.astrogrid.samp.web.CredentialPresenter;

/**
 * CredentialPresenter implementation suitable for use with the TLS profile.
 * This is a singleton class; see {@link #INSTANCE}.
 *
 * <p>Uses the following securityMap items:
 * <dl>
 * <dt>samp.name:</dt>
 * <dd>Self-declared client name.
 *     Mandatory, but since it's supplied by the client, it doesn't tell
 *     you anything trustworthy.
 *     </dd>
 * <dt>samp.referer:</dt>
 * <dd>Connection URL, present in originating XML-RPC call HTTP request
 *     headers at whim of browser, and inserted into identity-info map
 *     by relay.
 *     See <a href="https://www.w3.org/Protocols/rfc2616/rfc2616.html"
 *            >HTTP/1.1 (RFC2616)</a>, sec 14.36.
 *     It's not reliable from the hub's point of view since:
 *     <ul>
 *     <li>the browser might not have inserted it when the web application
 *         connected to the relay</li>
 *     <li>the connection to the relay might have come from something that
 *         is not a web application at all, hence could write its own
 *         Referer value</li>
 *     <li>the relay might be lying about it</li>
 *     </ul>
 *     </dd>
 * <dt>jsamp.relay</dt>
 * <dd>URL of the relay service; this is added during JSAMP processing
 *     rather than supplied by the client, and is reliable.</dd>
 * </dl>
 * The HttpRequest is ignored, since no directly available (incoming)
 * request is associated with the registration attempt in the TLS profile.
 *
 * <p>The sole instance of this singleton class is {@link #INSTANCE}.
 *
 * @author   Mark Taylor
 * @since    20 Jun 2016
 */
public class TlsCredentialPresenter implements CredentialPresenter {

    /** Singleton instance. */
    public static final TlsCredentialPresenter INSTANCE =
        new TlsCredentialPresenter();

    /** Relay key. */
    public static final String RELAY_KEY = "jsamp.relay";

    /**
     * Private sole constructor prevents external instantiation.
     */
    private TlsCredentialPresenter() {
    }

    public Presentation
            createPresentation( HttpServer.Request request, Map securityMap,
                                AuthResourceBundle.Content authContent )
            throws SampException {

        String appName = ClientAuthorizers.getAppName( securityMap );
        Object refererObj = securityMap.get( TlsHubProfile.REFERER_KEY );
        String referer = refererObj instanceof String ? (String) refererObj
                                                      : null;
        Object relayObj = securityMap.get( RELAY_KEY );
        String relay = relayObj instanceof String ? (String) relayObj : null;
        final Map map = new LinkedHashMap();
        map.put( authContent.nameWord(), appName );
        map.put( "URL", referer );
        map.put( "Relay", relay );
        List lines = new ArrayList();
        if ( relay == null || relay.trim().length() == 0 ) {
            lines.add( "WARNING: unknown relay, shouldn't happen (hub bug?)" );
        }
        else {
            String proto;
            try {
                proto = new URL( relay ).getProtocol();
            }
            catch ( MalformedURLException e ) {
                proto = "???";
            }
            if ( ! "https".equalsIgnoreCase( proto ) ) {
                lines.add( "INFO: hub relay service not https"
                         + ", identity is not guaranteed" );
            }
        }
        final Object[] msg = lines.toArray();
        return new Presentation() {
            public Map getAuthEntries() {
                return map;
            }
            public Object[] getAuthLines() {
                return msg;
            }
        };
    }
}
