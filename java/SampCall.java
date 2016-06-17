package org.astrogrid.samp.tls;

import java.util.List;
import java.util.Map;
import org.astrogrid.samp.SampMap;

/**
 * Represents a call made using one of the SAMP APIs.
 *
 * @author   Mark Taylor
 * @since    11 Mar 2016
 */
public class SampCall extends SampMap {

    /** Key for the callback XML-RPC method name (a string). */
    public static final String METHOD_NAME_KEY = "samp.methodName";

    /** Key for the callback parameters (a list). */
    public static final String PARAMS_KEY = "samp.params";

    /** Key for the HTTP Referer header value (a string), if known. */
    public static final String REFERER_KEY = "samp.referer";

    private static final String[] KNOWN_KEYS = new String[] {
        METHOD_NAME_KEY,
        PARAMS_KEY,
        REFERER_KEY,
    };

    /**
     * Constructs an empty call.
     */
    public SampCall() {
        super( KNOWN_KEYS );
    }

    /**
     * Constructs a call based on an existing map.
     *
     * @param  map   map containing initial data for this object
     */
    public SampCall( Map map ) {
        this();
        putAll( map );
    }

    /**
     * Constructs a call with its essential data filled in.
     *
     * @param  methodName  name of XML-RPC method
     * @param  params   list of SAMP-friendly parameter objects
     */
    public SampCall( String methodName, List params ) {
        this();
        put( METHOD_NAME_KEY, methodName );
        put( PARAMS_KEY, params );
    }

    /**
     * Returns the name of the XML-RPC method represented by this call.
     *
     * @return  method name
     */
    public String getMethodName() {
        return getString( METHOD_NAME_KEY );
    }

    /**
     * Returns a list of parameters for this call.
     *
     * @return  parameter list
     */
    public List getParams() {
        return getList( PARAMS_KEY );
    }

    /**
     * Returns a given map as a SampCall object.
     *
     * @param  map   map
     * @return  call
     */
    public static SampCall asCall( Map map ) {
        return ( map instanceof SampCall || map == null )
             ? (SampCall) map
             : new SampCall( map );
    }
}
