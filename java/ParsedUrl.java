package org.astrogrid.samp.tls;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Makes sense of a partial URL with parameters.
 *
 * @author   Mark Taylor
 * @since    21 Jun 2016
 */
class ParsedUrl {

    private final String path_;
    private final Map<String,String> params_;
    private static final String ENC = "UTF-8";
    private static final Logger logger_ =
        Logger.getLogger( ParsedUrl.class.getName() );

    /**
     * Constructor.
     *
     * @param   localPart   local part of URL
     */
    public ParsedUrl( String localPart ) {
        int iq = localPart.indexOf( '?' ); 
        final String query;
        if ( iq >= 0 ) {
            path_ = urlDecode( localPart.substring( 0, iq ) );
            query = localPart.substring( iq + 1 );
        }
        else {
            path_ = localPart;
            query = "";
        }
        params_ = new LinkedHashMap<String,String>();
        if ( query.trim().length() > 0 ) {
            for ( String paramPart : query.split( "&" ) ) {
                int ie = paramPart.indexOf( '=' );
                final String key;
                final String value;
                if ( ie >= 0 ) {
                    key = paramPart.substring( 0, ie );
                    value = paramPart.substring( ie + 1 );
                }
                else {
                    key = paramPart;
                    value = "";
                }
                params_.put( urlDecode( key ), urlDecode( value ) );
            }
        }
    }

    /**
     * Returns the path part of the supplied URL.
     *
     * @return   localPart excluding query part
     */
    public String getPath() {
        return path_;
    }

    /**
     * Returns an ordered map of the name=value parameters.
     *
     * @return  name-&gt;value parameter map
     */
    public Map<String,String> getParams() {
        return params_;
    }

    /**
     * Decodes text.
     *
     * @param   txt  input string
     * @return   unescaped text
     */
    private static final String urlDecode( String txt ) {
        String encoding = "UTF-8";
        try {
            return URLDecoder.decode( txt, encoding );
        }
        catch ( UnsupportedEncodingException e ) {
            logger_.warning( "Unsupported encoding " + encoding + "???" );
            return txt;
        }
    }
}
