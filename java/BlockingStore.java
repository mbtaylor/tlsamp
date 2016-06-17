package org.astrogrid.samp.tls;

import java.util.HashMap;
import java.util.Map;

/**
 * This is a data structure suitable for concurrent use that stores
 * objects under a key (like a Map), and provides a blocking method for
 * retrieving them.
 *
 * <p>The general expectation is that a given key will be placed into
 * the map once {@link #putNew}, and removed once {@link #take},
 * but those calls may be received in any order.
 *
 * @param  <K>  key type
 * @param  <V>  value type
 *
 * @author   Mark Taylor
 * @since    15 Jun 2016
 */
public class BlockingStore<K,V> {

    private final Map<K,V> map_;

    /**
     * Constructor.
     */
    public BlockingStore() {
        map_ = new HashMap<K,V>();
    }

    /**
     * Adds a new entry to the map, if no entry under the given key is
     * currently present.  If an entry for the given key is present,
     * the map is not changed.
     *
     * @param   key  key
     * @param   value   new value to associate with <code>key</code>
     * @return   true if the entry was added,
     *           false if not (because an entry for that key already existed)
     */
    public boolean putNew( K key, V value ) {
        synchronized ( map_ ) {
            if ( ! map_.containsKey( key ) ) {
                map_.put( key, value );
                map_.notifyAll();
                return true;
            }
            else {
                return false;
            }
        }
    }

    /**
     * Blocks until an entry with the given key is available, then returns it.
     *
     * @param  key  key
     * @param  waitMillis  maximum number of milliseconds to block for
     * @return   value associated with <code>key</code> if it is available at
     *           call time or becomes available within the specified wait time,
     *           otherwise null
     */
    public V take( K key, long waitMillis ) throws InterruptedException {
        long endTime = System.currentTimeMillis() + waitMillis;
        synchronized ( map_ ) {
            for ( long wait;
                  ! map_.containsKey( key ) &&
                  ( wait = endTime - System.currentTimeMillis() ) > 0; ) {
                map_.wait( wait );
            }
            if ( map_.containsKey( key ) ) {
                V value = map_.remove( key );
                map_.notifyAll();
                return value;
            }
            else {
                return null;
            }
        }
    }

    /**
     * Blocks for as long as a specified key remains in the map,
     * and then returns.  If the key is still in the map by the end of
     * the specified timeout period, it is removed.
     * On exit, the key will be absent from the map.
     *
     * @param  key  key
     * @param  waitMillis  maximum number of milliseconds to block for
     * @return   true iff this method removed the given entry, because
     *           it had not been taken within the given timeout
     */
    public boolean removeUntaken( K key, long waitMillis )
            throws InterruptedException {
        long endTime = System.currentTimeMillis() + waitMillis;
        synchronized ( map_ ) {
            for ( long wait;
                  map_.containsKey( key ) &&
                  ( wait = endTime - System.currentTimeMillis() ) > 0; ) {
                map_.wait( wait );
            }
            if ( map_.containsKey( key ) ) {
                map_.remove( key );
                map_.notifyAll();
                return true;
            }
            else {
                return false;
            }
        }
    }
}
