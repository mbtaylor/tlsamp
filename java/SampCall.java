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

    /** Key for the callback operation name (a string). */
    public static final String OPERATION_NAME_KEY = "samp.methodName";

    /** Key for the callback parameters (a list). */
    public static final String PARAMS_KEY = "samp.params";

    /** Key for a tag (a string) identifying a call for later matching. */
    public static final String TAG_KEY = "samp.callTag";

    /** Key for the HTTP Referer header value (a string), if known. */
    public static final String REFERER_KEY = "samp.referer";

    private static final String[] KNOWN_KEYS = new String[] {
        OPERATION_NAME_KEY,
        PARAMS_KEY,
        TAG_KEY,
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
     * @param  operationName  name in SAMP API of operation
     * @param  params   list of SAMP-friendly parameter objects
     * @param  callTag   string identifier, should be unique
     */
    public SampCall( String operationName, List params, String callTag ) {
        this();
        put( OPERATION_NAME_KEY, operationName );
        put( PARAMS_KEY, params );
        put( TAG_KEY, callTag );
    }

    /**
     * Returns the name of the operation represented by this call
     * from the SAMP API.
     *
     * @return  operation name
     */
    public String getOperationName() {
        return getString( OPERATION_NAME_KEY );
    }

    /**
     * Returns a list of parameters for this operation.
     *
     * @retrun  parameter list
     */
    public List getParams() {
        return getList( PARAMS_KEY );
    }

    /**
     * Returns a string identifying this call.  Should be unique, it can
     * be used for matching the call up with its response at a later date.
     *
     * @return  tag string
     */
    public String getCallTag() {
        return getString( TAG_KEY );
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
