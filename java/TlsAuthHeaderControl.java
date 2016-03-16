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
 *     </dd>
 * <dt>Proxy-Hub:</dt>
 * <dd>Dummy header introduced by the TLS Hub Profile, giving the URL
 *     of the proxy hub service from which pending calls were retrieved.
 *     It is reliable if HTTPS, since the hub profile gets information
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

    /** "Proxy-Hub", dummy header introduced by TLS hub profile. */
    public static final String PROXYHUB_HDR = "Proxy-Hub";

    /** 
     * Private constructor prevents public instantiation of singleton class.
     */
    private TlsAuthHeaderControl() {
    }

    public String[] getDisplayedHeaders() {
        return new String[] { REFERER_HDR, PROXYHUB_HDR };
    }

    public Object[] getMessageLines( Map hdrMap ) {
        String proxyHub = HttpServer.getHeader( hdrMap, PROXYHUB_HDR );
        List<String> lines = new ArrayList<String>();
        if ( proxyHub == null || proxyHub.trim().length() == 0 ) {
            lines.add( "WARNING: unknown proxy hub, shouldn't happen"
                     + " (hub bug?)" );
        }
        else {
            String proto;
            try {
                proto = new URL( proxyHub ).getProtocol();
            }
            catch ( MalformedURLException e ) {
                proto = "???";
            }
            if ( ! "https".equalsIgnoreCase( proto ) ) {
                lines.add( "INFO: proxy hub not https"
                         + ", identity is not guaranteed" );
            }
        }
        return lines.toArray();
    }
}
