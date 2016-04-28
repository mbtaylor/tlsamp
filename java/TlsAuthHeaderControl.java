package org.astrogrid.samp.tls;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.astrogrid.samp.httpd.HttpServer;
import org.astrogrid.samp.web.AuthHeaderControl;
import org.astrogrid.samp.web.WebAuthHeaderControl;

/**
 * AuthHeaderControl implementation suitable for use with the TLS profile.
 * This is a singleton class; see {@link #INSTANCE}.
 *
 * <p>Uses the following headers:
 * <dl>
 * <dt>Referer:</dt>
 * <dd>Connection URL, present at whim of browser.
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
 * <dt>Hub-Relay:</dt>
 * <dd>Dummy header introduced by the TLS Hub Profile, giving the URL
 *     of the relay service from which pending calls were retrieved.
 *     It is reliable over HTTPS, since the hub profile gets information
 *     about who it's talking to directly.
 *     </dd>
 * </dl>
 *
 * @author   Mark Taylor
 * @since    16 Mar 2016
 */
class TlsAuthHeaderControl implements AuthHeaderControl {

    /** Singleton instance. */
    public static final TlsAuthHeaderControl INSTANCE =
        new TlsAuthHeaderControl();

    /** Referer header. */
    public static final String REFERER_HDR = WebAuthHeaderControl.REFERER_HDR;

    /** "Hub-Relay", dummy header introduced by TLS hub profile. */
    public static final String RELAY_HDR = "Hub-Relay";

    /** 
     * Private constructor prevents public instantiation of singleton class.
     */
    private TlsAuthHeaderControl() {
    }

    public String[] getDisplayedHeaders() {
        return new String[] { REFERER_HDR, RELAY_HDR };
    }

    public Object[] getMessageLines( Map hdrMap ) {
        String relay = HttpServer.getHeader( hdrMap, RELAY_HDR );
        List<String> lines = new ArrayList<String>();
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
        return lines.toArray();
    }
}
