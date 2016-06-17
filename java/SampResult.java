package org.astrogrid.samp.tls;

import java.util.Map;
import org.astrogrid.samp.SampMap;

/**
 * Represents the result of a call made using one of the SAMP APIs.
 * To represent the result of a call, exactly one of
 * {@link #VALUE_KEY} or {@link #ERROR_KEY} entries should be present
 * in the map.
 *
 * @author   Mark Taylor
 * @since    11 Mar 2016
 */
public class SampResult extends SampMap {

    /** Key for the return value (object) resulting from call success. */
    public static final String VALUE_KEY = "samp.value";

    /** Key for the error message (string) resulting from call failure. */
    public static final String ERROR_KEY = "samp.error";

    private static final String[] KNOWN_KEYS = new String[] {
        VALUE_KEY,
        ERROR_KEY,
    };

    /**
     * Constructs an empty result.
     */
    public SampResult() {
        super( KNOWN_KEYS );
    }

    /**
     * Constructs a result based on an existing map.
     *
     * @param  map   map containing initial data for this object
     */
    public SampResult( Map map ) {
        this();
        putAll( map );
    }

    /**
     * Returns the return value resulting from call success.
     * If the call did not succeed, this will be null.
     *
     * @return  call return value, if any
     */
    public Object getValue() {
        return get( VALUE_KEY );
    }

    /**
     * Returns the error string resulting from call failure.
     * If the call did not fail, this will be null.
     *
     * @return   error string, if any
     */
    public String getError() {
        return getString( ERROR_KEY );
    }

    /**
     * Returns a result object representing a successful call.
     *
     * @param  value  call return value, should not be null
     * @return  success result
     */
    public static SampResult createSuccessResult( Object value ) {
        SampResult result = new SampResult();
        result.put( VALUE_KEY, value );
        return result;
    }

    /**
     * Returns a result object representing a failed call.
     *
     * @param   error  call error message, should not be null
     * @return   failure result
     */
    public static SampResult createErrorResult( String error ) {
        SampResult result = new SampResult();
        result.put( ERROR_KEY, error );
        return result;
    }

    /**
     * Returns a given map as a SampResult object.
     *
     * @param  map  map
     * @return   result
     */
    public static SampResult asResult( Map map ) {
        return map instanceof SampResult || map == null
             ? (SampResult) map
             : new SampResult( map );
    }
}
