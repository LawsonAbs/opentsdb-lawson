// This file is part of OpenTSDB.
// Copyright (C) 2010-2012  The OpenTSDB Authors.
//
// This program is free software: you can redistribute it and/or modify it
// under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 2.1 of the License, or (at your
// option) any later version.  This program is distributed in the hope that it
// will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
// of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser
// General Public License for more details.  You should have received a copy
// of the GNU Lesser General Public License along with this program.  If not,
// see <http://www.gnu.org/licenses/>.
package net.opentsdb.uid;

import java.nio.charset.Charset;
import java.util.*;

import java.util.concurrent.ConcurrentHashMap;

import javax.xml.bind.DatatypeConverter;

import com.stumbleupon.async.Callback;
import com.stumbleupon.async.Deferred;

import org.hbase.async.AtomicIncrementRequest;
import org.hbase.async.Bytes;
import org.hbase.async.DeleteRequest;
import org.hbase.async.GetRequest;
import org.hbase.async.HBaseClient;
import org.hbase.async.HBaseException;
import org.hbase.async.KeyValue;
import org.hbase.async.PutRequest;
import org.hbase.async.Scanner;
import org.hbase.async.Bytes.ByteMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.opentsdb.core.Const;
import net.opentsdb.core.Internal;
import net.opentsdb.core.TSDB;
import net.opentsdb.meta.UIDMeta;

/**
 * Represents a table of Unique IDs, manages the lookup and creation of IDs.
 * 代表唯一IDs的一张表，管理IDs的查询和创建
 *
 *
 * Don't attempt to use {@code equals()} or {@code hashCode()} on
 * this class.
 * 不要在这个类上使用equals()和hashcode()这两个方法
 * @see UniqueIdInterface
 */
@SuppressWarnings("deprecation")  // Dunno why even with this, compiler warns.
public final class UniqueId implements UniqueIdInterface {
  private static final Logger LOG = LoggerFactory.getLogger(UniqueId.class);

  /** Enumerator for different types of UIDS @since 2.0
   * UIDs 仅仅只有三种对象：Metric,Tag k,Tag v.
   * */
  public enum UniqueIdType {
    METRIC,
    TAGK,
    TAGV
  }
  
  /** Charset used to convert Strings to byte arrays and back. */
  //用于将字符串<->字节数组的编码字符集 =》这里使用的是ISO-8859-1
  private static final Charset CHARSET = Charset.forName("ISO-8859-1");

  /** The single column family used by this class. */
  private static final byte[] ID_FAMILY = toBytes("id");
  /** The single column family used by this class. */
  private static final byte[] NAME_FAMILY = toBytes("name");
  /** Row key of the special row used to track the max ID already assigned. */
  private static final byte[] MAXID_ROW = { 0 };
  /** How many time do we try to assign an ID before giving up. */
  private static final short MAX_ATTEMPTS_ASSIGN_ID = 3;
  /** How many time do we try to apply an edit before giving up. */
  private static final short MAX_ATTEMPTS_PUT = 6;
  /** How many time do we try to assign a random ID before giving up. */
  private static final short MAX_ATTEMPTS_ASSIGN_RANDOM_ID = 10;
  /** Initial delay in ms for exponential backoff to retry failed RPCs. */
  private static final short INITIAL_EXP_BACKOFF_DELAY = 800;
  /** Maximum number of results to return in suggest(). */
  private static final short MAX_SUGGESTIONS = 25;

  /** HBase client to use.
   * 1.A fully asynchronous, thread-safe, modern HBase client
   * 一个完全异步的，线程安全的，流行的HBase 客户端
   * */
  private final HBaseClient client;
  /** Table where IDs are stored.  */
  private final byte[] table;

  /** The kind of UniqueId, used as the column qualifier. */
  //UniqueId的类型，作为column qualifier 使用
  private final byte[] kind;


  /** The type of UID represented by this cache */
  private final UniqueIdType type;

  /** Number of bytes on which each ID is encoded. */
  //每个编码ID的字节数目
  //这个值在构造函数中被赋值
  private final short id_width;

  /** Whether or not to randomize new IDs
   *  是否是随机生成一个新的IDs
   * */
  private final boolean randomize_id;

  /** Cache for forward mappings (name to ID). */
  //预缓冲name到ID的映射
  //Creates a new, empty map with the default initial table size (16). ->创建一个新的，空的map，使用默认的初始表大小（16）
  //ConcurrentHashMap：A hash table supporting full concurrency of retrievals and high expected concurrency for updates.
//一个哈希表：支持检索完全的并行，并且为更新的高期待的并发
  private final ConcurrentHashMap<String, byte[]> name_cache =
    new ConcurrentHashMap<String, byte[]>();

  /** Cache for backward mappings (ID to name).
   * The ID in the key is a byte[] converted to a String to be Comparable. */
  private final ConcurrentHashMap<String, String> id_cache =
    new ConcurrentHashMap<String, String>();

  /** Map of pending UID assignments
   * 即将分配UID的 map 集合  —>需要使用map将Deferred对象保存起来，方便以后找到。【这个在Deferred类的介绍中可以详细了解】
   * */
  private final HashMap<String, Deferred<byte[]>> pending_assignments =
    new HashMap<String, Deferred<byte[]>>();

  /** Set of UID rename */
  private final Set<String> renaming_id_names =
    Collections.synchronizedSet(new HashSet<String>());

  /** Number of times we avoided reading from HBase thanks to the cache. */
  //由于cache的原因，我们避免从HBase中读取的次数【这里就是cache_hits:字面的意思就是：cache的点击数】
  private volatile int cache_hits;

  /** Number of times we had to read from HBase and populate the cache. */
  private volatile int cache_misses;
  /** How many times we collided with an existing ID when attempting to 
   * generate a new UID */
  private volatile int random_id_collisions;

  /** How many times assignments have been rejected by the UID filter
   * 因为过滤器的原因导致不能分配UID的次数
   * */
  private volatile int rejected_assignments;
  
  /** TSDB object used for filtering and/or meta generation. */
  //用于过滤并且/或者meta data生成目的的TSDB对象
  private TSDB tsdb;
  
  /**
   * Constructor.
   * @param client The HBase client to use.
   * @param table The name of the HBase table to use.
   * @param kind The kind of Unique ID this instance will deal with.
   * @param width The number of bytes on which Unique IDs should be encoded.
   * @throws IllegalArgumentException if width is negative or too small/large
   * or if kind is an empty string.
   */
  public UniqueId(final HBaseClient client, final byte[] table, final String kind,
                  final int width) {
    this(client, table, kind, width, false);
  }
  
  /**
   * Constructor.
   * @param client The HBase client to use.
   * @param table The name of the HBase table to use.
   * @param kind The kind of Unique ID this instance will deal with.
   * @param width The number of bytes on which Unique IDs should be encoded.
   * @param Whether or not to randomize new UIDs
   * @throws IllegalArgumentException if width is negative or too small/large
   * or if kind is an empty string.
   * @since 2.2
   */
  public UniqueId(final HBaseClient client, final byte[] table, final String kind,
                  final int width, final boolean randomize_id) {
    this.client = client;
    this.table = table;
    if (kind.isEmpty()) {
      throw new IllegalArgumentException("Empty string as 'kind' argument!");
    }
    this.kind = toBytes(kind);
    type = stringToUniqueIdType(kind);
    if (width < 1 || width > 8) {
      throw new IllegalArgumentException("Invalid width: " + width);
    }
    this.id_width = (short) width;
    this.randomize_id = randomize_id;
  }
  
  /**
   * Constructor.
   * @param tsdb The TSDB this UID object belongs to
   *             这个UID对象所属于的TSDB对象
   * @param table The name of the HBase table to use.
   *              需要使用到的HBase table
   * @param kind The kind of Unique ID this instance will deal with.
   *             这个实例将会处理的Unique ID类型
   * @param width The number of bytes on which Unique IDs should be encoded.
   *              Unique IDs将会被编码的字节数
   *
   * @param randomize_id Whether or not to randomize new UIDs
   *                      是否需要随机化新的UIDs
   * @throws IllegalArgumentException if width is negative or too small/large
   * or if kind is an empty string.
   * @since 2.3
   */
  public UniqueId(final TSDB tsdb, final byte[] table, final String kind,
                  final int width, final boolean randomize_id) {
    this.client = tsdb.getClient();
    this.tsdb = tsdb;
    this.table = table;
    if (kind.isEmpty()) {
      throw new IllegalArgumentException("Empty string as 'kind' argument!");
    }
    this.kind = toBytes(kind);
    type = stringToUniqueIdType(kind);
    if (width < 1 || width > 8) {
      throw new IllegalArgumentException("Invalid width: " + width);
    }
    this.id_width = (short) width;
    this.randomize_id = randomize_id;
  }

  /** The number of times we avoided reading from HBase thanks to the cache. */
  public int cacheHits() {
    return cache_hits;
  }

  /** The number of times we had to read from HBase and populate the cache. */
  public int cacheMisses() {
    return cache_misses;
  }

  /** Returns the number of elements stored in the internal cache. */
  public int cacheSize() {
    return name_cache.size() + id_cache.size();
  }

  /** Returns the number of random UID collisions */
  public int randomIdCollisions() {
    return random_id_collisions;
  }
  
  /** Returns the number of UID assignments rejected by the filter */
  public int rejectedAssignments() {
    return rejected_assignments;
  }


  //a function, return
  public String kind() {
    return fromBytes(kind);
  }

  public short width() {
    return id_width;
  }

  /** @param tsdb Whether or not to track new UIDMeta objects */
  public void setTSDB(final TSDB tsdb) {
    this.tsdb = tsdb;
  }
  
  /** The largest possible ID given the number of bytes the IDs are 
   * represented on.
   * @deprecated Use {@link Internal.getMaxUnsignedValueOnBytes}
   */
  public long maxPossibleId() {
    return Internal.getMaxUnsignedValueOnBytes(id_width);
  }
  
  /**
   * Causes this instance to discard all its in-memory caches.
   * 导致这个实例丢弃内存中的所有缓存
   * @since 1.1
   */
  public void dropCaches() {
    name_cache.clear();
    id_cache.clear();
  }

  /**
   * Finds the name associated with a given ID.
   * <p>
   * <strong>This method is blocking.</strong>  Its use within OpenTSDB itself
   * is discouraged, please use {@link #getNameAsync} instead.
   * @param id The ID associated with that name.
   * @see #getId(String)
   * @see #getOrCreateId(String)
   * @throws NoSuchUniqueId if the given ID is not assigned.
   * @throws HBaseException if there is a problem communicating with HBase.
   * @throws IllegalArgumentException if the ID given in argument is encoded
   * on the wrong number of bytes.
   */
  public String getName(final byte[] id) throws NoSuchUniqueId, HBaseException {
    try {
      return getNameAsync(id).joinUninterruptibly();
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("Should never be here", e);
    }
  }

  /**
   * Finds the name associated with a given ID.
   *
   * @param id The ID associated with that name.
   * @see #getId(String)
   * @see #getOrCreateIdAsync(String)
   * @throws NoSuchUniqueId if the given ID is not assigned.
   * @throws HBaseException if there is a problem communicating with HBase.
   * @throws IllegalArgumentException if the ID given in argument is encoded
   * on the wrong number of bytes.
   * @since 1.1
   */
  public Deferred<String> getNameAsync(final byte[] id) {
    if (id.length != id_width) {
      throw new IllegalArgumentException("Wrong id.length = " + id.length
                                         + " which is != " + id_width
                                         + " required for '" + kind() + '\'');
    }
    final String name = getNameFromCache(id);
    if (name != null) {
      cache_hits++;
      return Deferred.fromResult(name);
    }
    cache_misses++;
    class GetNameCB implements Callback<String, String> {
      public String call(final String name) {
        if (name == null) {
          throw new NoSuchUniqueId(kind(), id);
        }
        addNameToCache(id, name);
        addIdToCache(name, id);        
        return name;
      }
    }
    return getNameFromHBase(id).addCallback(new GetNameCB());
  }

  private String getNameFromCache(final byte[] id) {
    return id_cache.get(fromBytes(id));
  }

  private Deferred<String> getNameFromHBase(final byte[] id) {
    class NameFromHBaseCB implements Callback<String, byte[]> {
      public String call(final byte[] name) {
        return name == null ? null : fromBytes(name);
      }
    }
    return hbaseGet(id, NAME_FAMILY).addCallback(new NameFromHBaseCB());
  }

  private void addNameToCache(final byte[] id, final String name) {
    final String key = fromBytes(id);
    String found = id_cache.get(key);
    if (found == null) {
      found = id_cache.putIfAbsent(key, name);
    }
    if (found != null && !found.equals(name)) {
      throw new IllegalStateException("id=" + Arrays.toString(id) + " => name="
          + name + ", already mapped to " + found);
    }
  }

  //call -> getIdAsync(name)
  public byte[] getId(final String name) throws NoSuchUniqueName, HBaseException {
    try {
      return getIdAsync(name).joinUninterruptibly();
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("Should never be here", e);
    }
  }

  //返回对象是一个Deferred<byte[]>类型
  public Deferred<byte[]> getIdAsync(final String name) {
    //从内存中检索name对应的id --> the result is byte[]
      final byte[] id = getIdFromCache(name); //getIdFromCache()其实是一个方法，内部调用了name_cache[ConcurrentHashMap类型]
    if (id != null) {
      cache_hits++;//cache访问数增加
      return Deferred.fromResult(id);//返回一个Deferred对象
    }
    cache_misses++;//否则cache_misses增加 -->表示没有在cache中查询到

      //类GetIdCB实现Callback，那么GetIdCB就是一个Callback了
    class GetIdCB implements Callback<byte[], byte[]> {//实现Callback()接口
        //下面这个call()方法是 覆写Callback接口中的
        public byte[] call(final byte[] id) {
        if (id == null) {
          throw new NoSuchUniqueName(kind(), name);
        }

        //如果二者长度不等【说明id的长度不符合规范】 --> 抛出异常
        if (id.length != id_width) {
          throw new IllegalStateException("Found id.length = " + id.length
                                          + " which is != " + id_width
                                          + " required for '" + kind() + '\'');
        }
        addIdToCache(name, id);
        addNameToCache(id, name);
        return id;
      }
      public String toString(){
          return "this function is added by LittleLawson";
      }
    }
    //getIdFromHBase(name)这个应该会发出一个RPC  所以将这个请求结果添加了一个回调函数
    Deferred<byte[]> d = getIdFromHBase(name).addCallback(new GetIdCB());
    return d;
  }

  private byte[] getIdFromCache(final String name) {
    return name_cache.get(name);
  }

    /**1.getIdFromHBase方法  --> 调用hbaseGet()方法
     * 2.远程调用hbaseGet(toByted(name),ID_FAMILY)方法是一个RPC过程，
     * 所以肯定会使用一个多线程的方式去实现这个方法【而不是让主线程自己执行这段代码】

     * @param name
     * @return
     */
  private Deferred<byte[]> getIdFromHBase(final String name) {
    return hbaseGet(toBytes(name), ID_FAMILY);
  }

  private void addIdToCache(final String name, final byte[] id) {
    byte[] found = name_cache.get(name);
    if (found == null) {
      found = name_cache.putIfAbsent(name,
                                    // Must make a defensive copy to be immune
                                    // to any changes the caller may do on the
                                    // array later on.
                                    Arrays.copyOf(id, id.length));
    }
    if (found != null && !Arrays.equals(found, id)) {
      throw new IllegalStateException("name=" + name + " => id="
          + Arrays.toString(id) + ", already mapped to "
          + Arrays.toString(found));
    }
  }

  /**
   * Implements the process to allocate a new UID.
   * 实现分配一个新的UID的过程
   *
   * This callback is re-used multiple times in a four step process:
   *   1. Allocate a new UID via atomic increment.
   *   2. Create the reverse mapping (ID to name).
   *   3. Create the forward mapping (name to ID).
   *   4. Return the new UID to the caller.
   * 这个过程会被多次重复使用在如下四个步骤中：
   *  01.通过原子增加的方式，分配一个新的UID
   *  02.创建一个逆映射（ID -> name）
   *  03.创建一个顺映射（name -> ID）
   *  04.给调用者返回一个新的UID
   *
   */
  private final class UniqueIdAllocator implements Callback<Object, Object> {
    private final String name;  // What we're trying to allocate an ID for. -> 我们需要为哪个name分配一个ID
    private final Deferred<byte[]> assignment; // deferred to call back -> 需要回调的deferred
    private short attempt = randomize_id ?     // Give up when zero.
        MAX_ATTEMPTS_ASSIGN_RANDOM_ID : MAX_ATTEMPTS_ASSIGN_ID;

    private HBaseException hbe = null;  // Last exception caught.
    // TODO(manolama) - right now if we retry the assignment it will create a 
    // callback chain MAX_ATTEMPTS_* long and call the ErrBack that many times.
    // This can be cleaned up a fair amount but it may require changing the 
    // public behavior a bit. For now, the flag will prevent multiple attempts
    // to execute the callback.
    private boolean called = false; // whether we called the deferred or not  -> 我们是否调用了deferred？

    private long id = -1;  // The ID we'll grab with an atomic increment.
    private byte row[];    // The same ID, as a byte array.

    private static final byte ALLOCATE_UID = 0;
    private static final byte CREATE_REVERSE_MAPPING = 1;
    private static final byte CREATE_FORWARD_MAPPING = 2;
    private static final byte DONE = 3;

    //state表示的是当前这个线程需要执行的操作是什么。在这里就是分配UID
    private byte state = ALLOCATE_UID;  // Current state of the process.


    UniqueIdAllocator(final String name, final Deferred<byte[]> assignment) {
      this.name = name;
      this.assignment = assignment;
    }

    Deferred<byte[]> tryAllocate() {
      attempt--;
      state = ALLOCATE_UID;
      call(null);
      return assignment;
    }

    @SuppressWarnings("unchecked")
    public Object call(final Object arg) {
      if (attempt == 0) {
        if (hbe == null && !randomize_id) {
          throw new IllegalStateException("Should never happen!");
        }
        LOG.error("Failed to assign an ID for kind='" + kind()
                  + "' name='" + name + "'", hbe);
        if (hbe == null) {
          throw new FailedToAssignUniqueIdException(kind(), name, 
              MAX_ATTEMPTS_ASSIGN_RANDOM_ID);
        }
        throw hbe;
      }

      if (arg instanceof Exception) {
        final String msg = ("Failed attempt #" + (randomize_id
                         ? (MAX_ATTEMPTS_ASSIGN_RANDOM_ID - attempt) 
                         : (MAX_ATTEMPTS_ASSIGN_ID - attempt))
                         + " to assign an UID for " + kind() + ':' + name
                         + " at step #" + state);
        if (arg instanceof HBaseException) {
          LOG.error(msg, (Exception) arg);
          hbe = (HBaseException) arg;
          attempt--;
          state = ALLOCATE_UID;;  // Retry from the beginning.
        } else {
          LOG.error("WTF?  Unexpected exception!  " + msg, (Exception) arg);
          return arg;  // Unexpected exception, let it bubble up.
        }
      }

      class ErrBack implements Callback<Object, Exception> {
        public Object call(final Exception e) throws Exception {
          if (!called) {
            LOG.warn("Failed pending assignment for: " + name, e);
            assignment.callback(e);
            called = true;
          }
          return assignment;
        }
      }
      
      final Deferred d;
      switch (state) {
        case ALLOCATE_UID:
          d = allocateUid();
          break;
        case CREATE_REVERSE_MAPPING:
          d = createReverseMapping(arg);
          break;
        case CREATE_FORWARD_MAPPING:
          d = createForwardMapping(arg);
          break;
        case DONE:
          return done(arg);
        default:
          throw new AssertionError("Should never be here!");
      }
      return d.addBoth(this).addErrback(new ErrBack());
    }

    /** Generates either a random or a serial ID. If random, we need to
     * make sure that there isn't a UID collision.
     * 产生一个随机的或者是有序的ID。如果是随机的，我们需要确保没有一个UID会发生碰撞
     */
    private Deferred<Long> allocateUid() {
      LOG.info("Creating " + (randomize_id ? "a random " : "an ") + 
          "ID for kind='" + kind() + "' name='" + name + '\'');

      //这种修改state是为了什么？
      state = CREATE_REVERSE_MAPPING;
      if (randomize_id) {//如果是随机生成一个UID
        return Deferred.fromResult(RandomUniqueId.getRandomUID());
      } else {//否则生成一个自增值
          //atomicIncrement: Atomically and durably increments a value in HBase.
          //在Hbase中，原子增加并且持久化的增加一个值
          //直接发起一个rpc过程，往hbase表中写入数据
        return client.atomicIncrement(new AtomicIncrementRequest(table,MAXID_ROW, ID_FAMILY, kind));
      }
    }

    /**
     * Create the reverse mapping.
     * We do this before the forward one so that if we die before creating
     * the forward mapping we don't run the risk of "publishing" a
     * partially assigned ID.  The reverse mapping on its own is harmless
     * but the forward mapping without reverse mapping is bad as it would
     * point to an ID that cannot be resolved.
     */
    private Deferred<Boolean> createReverseMapping(final Object arg) {
      if (!(arg instanceof Long)) {
        throw new IllegalStateException("Expected a Long but got " + arg);
      }
      id = (Long) arg;
      if (id <= 0) {
        throw new IllegalStateException("Got a negative ID from HBase: " + id);
      }
      LOG.info("Got ID=" + id
               + " for kind='" + kind() + "' name='" + name + "'");
      row = Bytes.fromLong(id);
      // row.length should actually be 8.
      if (row.length < id_width) {
        throw new IllegalStateException("OMG, row.length = " + row.length
                                        + " which is less than " + id_width
                                        + " for id=" + id
                                        + " row=" + Arrays.toString(row));
      }
      // Verify that we're going to drop bytes that are 0.
      for (int i = 0; i < row.length - id_width; i++) {
        if (row[i] != 0) {
          final String message = "All Unique IDs for " + kind()
            + " on " + id_width + " bytes are already assigned!";
          LOG.error("OMG " + message);
          throw new IllegalStateException(message);
        }
      }
      // Shrink the ID on the requested number of bytes.
      row = Arrays.copyOfRange(row, row.length - id_width, row.length);

      state = CREATE_FORWARD_MAPPING;
      // We are CAS'ing the KV into existence -- the second argument is how
      // we tell HBase we want to atomically create the KV, so that if there
      // is already a KV in this cell, we'll fail.  Technically we could do
      // just a `put' here, as we have a freshly allocated UID, so there is
      // not reason why a KV should already exist for this UID, but just to
      // err on the safe side and catch really weird corruption cases, we do
      // a CAS instead to create the KV.
      return client.compareAndSet(reverseMapping(), HBaseClient.EMPTY_ARRAY);
    }

    private PutRequest reverseMapping() {
      return new PutRequest(table, row, NAME_FAMILY, kind, toBytes(name));
    }

    private Deferred<?> createForwardMapping(final Object arg) {
      if (!(arg instanceof Boolean)) {
        throw new IllegalStateException("Expected a Boolean but got " + arg);
      }
      if (!((Boolean) arg)) {  // Previous CAS failed. 
        if (randomize_id) {
          // This random Id is already used by another row
          LOG.warn("Detected random id collision and retrying kind='" + 
              kind() + "' name='" + name + "'");
          random_id_collisions++;
        } else {
          // something is really messed up then
          LOG.error("WTF!  Failed to CAS reverse mapping: " + reverseMapping()
              + " -- run an fsck against the UID table!");
        }
        attempt--;
        state = ALLOCATE_UID;
        return Deferred.fromResult(false);
      }

      state = DONE;
      return client.compareAndSet(forwardMapping(), HBaseClient.EMPTY_ARRAY);
    }

    private PutRequest forwardMapping() {
        return new PutRequest(table, toBytes(name), ID_FAMILY, kind, row);
    }

    private Deferred<byte[]> done(final Object arg) {
      if (!(arg instanceof Boolean)) {
        throw new IllegalStateException("Expected a Boolean but got " + arg);
      }
      if (!((Boolean) arg)) {  // Previous CAS failed.  We lost a race.
        LOG.warn("Race condition: tried to assign ID " + id + " to "
                 + kind() + ":" + name + ", but CAS failed on "
                 + forwardMapping() + ", which indicates this UID must have"
                 + " been allocated concurrently by another TSD or thread. "
                 + "So ID " + id + " was leaked.");
        // If two TSDs attempted to allocate a UID for the same name at the
        // same time, they would both have allocated a UID, and created a
        // reverse mapping, and upon getting here, only one of them would
        // manage to CAS this KV into existence.  The one that loses the
        // race will retry and discover the UID assigned by the winner TSD,
        // and a UID will have been wasted in the process.  No big deal.
        if (randomize_id) {
          // This random Id is already used by another row
          LOG.warn("Detected random id collision between two tsdb "
              + "servers kind='" + kind() + "' name='" + name + "'");
          random_id_collisions++;
        }
        
        class GetIdCB implements Callback<Object, byte[]> {
          public Object call(final byte[] row) throws Exception {
            assignment.callback(row);
            return null;
          }
        }
        getIdAsync(name).addCallback(new GetIdCB());
        return assignment;
      }

      cacheMapping(name, row);
      
      if (tsdb != null && tsdb.getConfig().enable_realtime_uid()) {
        final UIDMeta meta = new UIDMeta(type, row, name);
        meta.storeNew(tsdb);
        LOG.info("Wrote UIDMeta for: " + name);
        tsdb.indexUIDMeta(meta);
      }
      
      synchronized(pending_assignments) {
        if (pending_assignments.remove(name) != null) {
          LOG.info("Completed pending assignment for: " + name);
        }
      }
      assignment.callback(row);
      return assignment;
    }

  }

  /** Adds the bidirectional mapping in the cache. */
  private void cacheMapping(final String name, final byte[] id) {
    addIdToCache(name, id);
    addNameToCache(id, name);
  } 
  
  /**
   * Finds the ID associated with a given name or creates it.
   * 寻找或者是创建与给出的名字相应的ID
   *
   * <p>
   * <strong>This method is blocking.</strong>  Its use within OpenTSDB itself
   * is discouraged, please use {@link #getOrCreateIdAsync} instead.
   * <p>
   * 这个方法是blocking（阻塞的）。它的使用在openTSDB中是不被鼓励的，相反请使用getOrCreateIdAsync
   *
   * The length of the byte array is fixed in advance by the implementation.
   * 数组的长度是固定的，由实现提前确定
   *
   * @param name The name to lookup in the table or to assign an ID to.
   *  在表中寻找的，亦或是即将分配ID的name
   * @throws HBaseException if there is a problem communicating with HBase.
   *  如果和HBase 通信有问题，则会抛出HBaseException
   * @throws IllegalStateException if all possible IDs are already assigned.
   *  如果所有可能的IDs均匀被分配，则抛出IllegalStateException
   * @throws IllegalStateException if the ID found in HBase is encoded on the
   *  wrong number of bytes.
   *  如果在HBase中发现的ID是用错误的字节数编码
   */
  public byte[] getOrCreateId(final String name) throws HBaseException {
    try {
        //异步调用，这里是先寻找name所对应的id是否存在，如果不存在的话，就需要分配一个id
      return getIdAsync(name).joinUninterruptibly();
    } catch (NoSuchUniqueName e) {


        //默认情况下，这个uidFilter是关闭的。所以下面这个操作是不会被执行的
      if (tsdb != null && tsdb.getUidFilter() != null && tsdb.getUidFilter().fillterUIDAssignments()) {
        try {
          if (!tsdb.getUidFilter()
                  .allowUIDAssignment(type, name, null, null)
                  .join()) {
            rejected_assignments++;
            throw new FailedToAssignUniqueIdException(new String(kind), name, 0, 
                "Blocked by UID filter.");
          }
        } catch (FailedToAssignUniqueIdException e1) {
          throw e1;
        } catch (InterruptedException e1) {
          LOG.error("Interrupted", e1);
          Thread.currentThread().interrupt();
        } catch (Exception e1) {
          throw new RuntimeException("Should never be here", e1);
        }
      }
      
      Deferred<byte[]> assignment = null; //是一个Deferred对象
      boolean pending = false;

      //进行同步操作，对pending_assignments这个对象
      synchronized (pending_assignments) {
        assignment = pending_assignments.get(name);
        if (assignment == null) {
          // to prevent UID leaks that can be caused when multiple time
          // series for the same metric or tags arrive, we need to write a 
          // deferred to the pending map as quickly as possible. Then we can 
          // start the assignment process after we've stashed the deferred 
          // and released the lock
            /*在有着相同的metric和tags的多个时间序列到达时，为了阻止UID泄漏，我们需要创建一个deferred对象去尽可能快地
            挂起地图。在我们已经存储deferred对象以及释放锁之后，我们就能够开始进程去分配。*/
          assignment = new Deferred<byte[]>();
          pending_assignments.put(name, assignment);
        } else {
          pending = true;
        }
      }

      if (pending) {//接下来就等待分配UID了
        LOG.info("Already waiting for UID assignment: " + name);
        try {
          return assignment.joinUninterruptibly();
        } catch (Exception e1) {
          throw new RuntimeException("Should never be here", e1);
        }
      }
      
      // start the assignment dance after stashing the deferred =>//stash:存放
        //在存放了deferred对象自后，开始分配的工作
      byte[] uid = null;
      try {
        uid = new UniqueIdAllocator(name, assignment).tryAllocate().joinUninterruptibly();
      } catch (RuntimeException e1) {
        throw e1;
      } catch (Exception e1) {
        throw new RuntimeException("Should never be here", e);
      } finally {
        synchronized (pending_assignments) {
          if (pending_assignments.remove(name) != null) {
            LOG.info("Completed pending assignment for: " + name);
          }
        }
      }
      return uid;
    } catch (Exception e) {
      throw new RuntimeException("Should never be here", e);
    }
  }
  
  /**
   * Finds the ID associated with a given name or creates it.
   * <p>
   * The length of the byte array is fixed in advance by the implementation.
   *
   * @param name The name to lookup in the table or to assign an ID to.
   * @throws HBaseException if there is a problem communicating with HBase.
   * @throws IllegalStateException if all possible IDs are already assigned.
   * @throws IllegalStateException if the ID found in HBase is encoded on the
   * wrong number of bytes.
   * @since 1.2
   */
  public Deferred<byte[]> getOrCreateIdAsync(final String name) {
    return getOrCreateIdAsync(name, null, null);
  }
  
  /**
   * Finds the ID associated with a given name or creates it.
   * <p>
   * The length of the byte array is fixed in advance by the implementation.
   *
   * @param name The name to lookup in the table or to assign an ID to.
   * @param metric Name of the metric associated with the UID for filtering.
   * @param tags Tag set associated with the UID for filtering.
   * @throws HBaseException if there is a problem communicating with HBase.
   * @throws IllegalStateException if all possible IDs are already assigned.
   * @throws IllegalStateException if the ID found in HBase is encoded on the
   * wrong number of bytes.
   * @since 2.3
   */
  public Deferred<byte[]> getOrCreateIdAsync(final String name, 
      final String metric, final Map<String, String> tags) {
    // Look in the cache first.
    final byte[] id = getIdFromCache(name);
    if (id != null) {
      cache_hits++;
      return Deferred.fromResult(id);
    }
    // Not found in our cache, so look in HBase instead.

    /** Triggers the assignment if allowed through the filter */
    class AssignmentAllowedCB implements  Callback<Deferred<byte[]>, Boolean> {
      @Override
      public Deferred<byte[]> call(final Boolean allowed) throws Exception {
        if (!allowed) {
          rejected_assignments++;
          return Deferred.fromError(new FailedToAssignUniqueIdException(
              new String(kind), name, 0, "Blocked by UID filter."));
        }
        
        Deferred<byte[]> assignment = null;
        synchronized (pending_assignments) {
          assignment = pending_assignments.get(name);
          if (assignment == null) {
            // to prevent UID leaks that can be caused when multiple time
            // series for the same metric or tags arrive, we need to write a 
            // deferred to the pending map as quickly as possible. Then we can 
            // start the assignment process after we've stashed the deferred 
            // and released the lock
            assignment = new Deferred<byte[]>();
            pending_assignments.put(name, assignment);
          } else {
            LOG.info("Already waiting for UID assignment: " + name);
            return assignment;
          }
        }
        
        // start the assignment dance after stashing the deferred
        if (metric != null && LOG.isDebugEnabled()) {
          LOG.debug("Assigning UID for '" + name + "' of type '" + type + 
              "' for series '" + metric + ", " + tags + "'");
        }
        
        // start the assignment dance after stashing the deferred
        return new UniqueIdAllocator(name, assignment).tryAllocate();
      }
      @Override
      public String toString() {
        return "AssignmentAllowedCB";
      }
    }
    
    /** Triggers an assignment (possibly through the filter) if the exception 
     * returned was a NoSuchUniqueName. */
    class HandleNoSuchUniqueNameCB implements Callback<Object, Exception> {
      public Object call(final Exception e) {
        if (e instanceof NoSuchUniqueName) {
          if (tsdb != null && tsdb.getUidFilter() != null && 
              tsdb.getUidFilter().fillterUIDAssignments()) {
            return tsdb.getUidFilter()
                .allowUIDAssignment(type, name, metric, tags)
                .addCallbackDeferring(new AssignmentAllowedCB());
          } else {
            return Deferred.fromResult(true)
                .addCallbackDeferring(new AssignmentAllowedCB());
          }
        }
        return e;  // Other unexpected exception, let it bubble up.
      }
    }

    // Kick off the HBase lookup, and if we don't find it there either, start
    // the process to allocate a UID.
    return getIdAsync(name).addErrback(new HandleNoSuchUniqueNameCB());
  }

  /**
   * Attempts to find suggestions of names given a search term.
   * <p>
   * <strong>This method is blocking.</strong>  Its use within OpenTSDB itself
   * is discouraged, please use {@link #suggestAsync} instead.
   * @param search The search term (possibly empty).
   * @return A list of known valid names that have UIDs that sort of match
   * the search term.  If the search term is empty, returns the first few
   * terms.
   * @throws HBaseException if there was a problem getting suggestions from
   * HBase.
   */
  public List<String> suggest(final String search) throws HBaseException {
    return suggest(search, MAX_SUGGESTIONS);
  }
      
  /**
   * Attempts to find suggestions of names given a search term.
   * @param search The search term (possibly empty).
   * @param max_results The number of results to return. Must be 1 or greater
   * @return A list of known valid names that have UIDs that sort of match
   * the search term.  If the search term is empty, returns the first few
   * terms.
   * @throws HBaseException if there was a problem getting suggestions from
   * HBase.
   * @throws IllegalArgumentException if the count was less than 1
   * @since 2.0
   */
  public List<String> suggest(final String search, final int max_results) 
    throws HBaseException {
    if (max_results < 1) {
      throw new IllegalArgumentException("Count must be greater than 0");
    }
    try {
      return suggestAsync(search, max_results).joinUninterruptibly();
    } catch (HBaseException e) {
      throw e;
    } catch (Exception e) {  // Should never happen.
      final String msg = "Unexpected exception caught by "
        + this + ".suggest(" + search + ')';
      LOG.error(msg, e);
      throw new RuntimeException(msg, e);  // Should never happen.
    }
  }

  /**
   * Attempts to find suggestions of names given a search term.
   * @param search The search term (possibly empty).
   * @return A list of known valid names that have UIDs that sort of match
   * the search term.  If the search term is empty, returns the first few
   * terms.
   * @throws HBaseException if there was a problem getting suggestions from
   * HBase.
   * @since 1.1
   */
  public Deferred<List<String>> suggestAsync(final String search, 
      final int max_results) {
    return new SuggestCB(search, max_results).search();
  }

  /**
   * Helper callback to asynchronously scan HBase for suggestions.
   */
  private final class SuggestCB
    implements Callback<Object, ArrayList<ArrayList<KeyValue>>> {
    private final LinkedList<String> suggestions = new LinkedList<String>();
    private final Scanner scanner;
    private final int max_results;

    SuggestCB(final String search, final int max_results) {
      this.max_results = max_results;
      this.scanner = getSuggestScanner(client, table, search, kind, max_results);
    }

    @SuppressWarnings("unchecked")
    Deferred<List<String>> search() {
      return (Deferred) scanner.nextRows().addCallback(this);
    }

    public Object call(final ArrayList<ArrayList<KeyValue>> rows) {
      if (rows == null) {  // We're done scanning.
        return suggestions;
      }
      
      for (final ArrayList<KeyValue> row : rows) {
        if (row.size() != 1) {
          LOG.error("WTF shouldn't happen!  Scanner " + scanner + " returned"
                    + " a row that doesn't have exactly 1 KeyValue: " + row);
          if (row.isEmpty()) {
            continue;
          }
        }
        final byte[] key = row.get(0).key();
        final String name = fromBytes(key);
        final byte[] id = row.get(0).value();
        final byte[] cached_id = name_cache.get(name);
        if (cached_id == null) {
          cacheMapping(name, id); 
        } else if (!Arrays.equals(id, cached_id)) {
          throw new IllegalStateException("WTF?  For kind=" + kind()
            + " name=" + name + ", we have id=" + Arrays.toString(cached_id)
            + " in cache, but just scanned id=" + Arrays.toString(id));
        }
        suggestions.add(name);
        if ((short) suggestions.size() >= max_results) {  // We have enough.
          return scanner.close().addCallback(new Callback<Object, Object>() {
            @Override
            public Object call(Object ignored) throws Exception {
              return suggestions;
            }
          });
        }
        row.clear();  // free()
      }
      return search();  // Get more suggestions.
    }
  }

  /**
   * Reassigns the UID to a different name (non-atomic).
   * <p>
   * Whatever was the UID of {@code oldname} will be given to {@code newname}.
   * {@code oldname} will no longer be assigned a UID.
   * <p>
   * Beware that the assignment change is <b>not atommic</b>.  If two threads
   * or processes attempt to rename the same UID differently, the result is
   * unspecified and might even be inconsistent.  This API is only here for
   * administrative purposes, not for normal programmatic interactions.
   * @param oldname The old name to rename.
   * @param newname The new name.
   * @throws NoSuchUniqueName if {@code oldname} wasn't assigned.
   * @throws IllegalArgumentException if {@code newname} was already assigned.
   * @throws HBaseException if there was a problem with HBase while trying to
   * update the mapping.
   */
  public void rename(final String oldname, final String newname) {
    final byte[] row = getId(oldname);
    final String row_string = fromBytes(row);
    {
      byte[] id = null;
      try {
        id = getId(newname);
      } catch (NoSuchUniqueName e) {
        // OK, we don't want the new name to be assigned.
      }
      if (id != null) {
        throw new IllegalArgumentException("When trying rename(\"" + oldname
          + "\", \"" + newname + "\") on " + this + ": new name already"
          + " assigned ID=" + Arrays.toString(id));
      }
    }

    if (renaming_id_names.contains(row_string)
        || renaming_id_names.contains(newname)) {
      throw new IllegalArgumentException("Ongoing rename on the same ID(\""
        + Arrays.toString(row) + "\") or an identical new name(\"" + newname
        + "\")");
    }
    renaming_id_names.add(row_string);
    renaming_id_names.add(newname);

    final byte[] newnameb = toBytes(newname);

    // Update the reverse mapping first, so that if we die before updating
    // the forward mapping we don't run the risk of "publishing" a
    // partially assigned ID.  The reverse mapping on its own is harmless
    // but the forward mapping without reverse mapping is bad.
    try {
      final PutRequest reverse_mapping = new PutRequest(
        table, row, NAME_FAMILY, kind, newnameb);
      hbasePutWithRetry(reverse_mapping, MAX_ATTEMPTS_PUT,
                        INITIAL_EXP_BACKOFF_DELAY);
    } catch (HBaseException e) {
      LOG.error("When trying rename(\"" + oldname
        + "\", \"" + newname + "\") on " + this + ": Failed to update reverse"
        + " mapping for ID=" + Arrays.toString(row), e);
      renaming_id_names.remove(row_string);
      renaming_id_names.remove(newname);
      throw e;
    }

    // Now create the new forward mapping.
    try {
      final PutRequest forward_mapping = new PutRequest(
        table, newnameb, ID_FAMILY, kind, row);
      hbasePutWithRetry(forward_mapping, MAX_ATTEMPTS_PUT,
                        INITIAL_EXP_BACKOFF_DELAY);
    } catch (HBaseException e) {
      LOG.error("When trying rename(\"" + oldname
        + "\", \"" + newname + "\") on " + this + ": Failed to create the"
        + " new forward mapping with ID=" + Arrays.toString(row), e);
      renaming_id_names.remove(row_string);
      renaming_id_names.remove(newname);
      throw e;
    }

    // Update cache.
    addIdToCache(newname, row);            // add     new name -> ID
    id_cache.put(fromBytes(row), newname);  // update  ID -> new name
    name_cache.remove(oldname);             // remove  old name -> ID

    // Delete the old forward mapping.
    try {
      final DeleteRequest old_forward_mapping = new DeleteRequest(
        table, toBytes(oldname), ID_FAMILY, kind);
      client.delete(old_forward_mapping).joinUninterruptibly();
    } catch (HBaseException e) {
      LOG.error("When trying rename(\"" + oldname
        + "\", \"" + newname + "\") on " + this + ": Failed to remove the"
        + " old forward mapping for ID=" + Arrays.toString(row), e);
      throw e;
    } catch (Exception e) {
      final String msg = "Unexpected exception when trying rename(\"" + oldname
        + "\", \"" + newname + "\") on " + this + ": Failed to remove the"
        + " old forward mapping for ID=" + Arrays.toString(row);
      LOG.error("WTF?  " + msg, e);
      throw new RuntimeException(msg, e);
    } finally {
      renaming_id_names.remove(row_string);
      renaming_id_names.remove(newname);
    }
    // Success!
  }

  /**
   * Attempts to remove the mappings for the given string from the UID table
   * as well as the cache. If used, the caller should remove the entry from all
   * TSD caches as well.
   * <p>
   * WARNING: This is a best attempt only method in that we'll lookup the UID
   * for the given string, then issue two delete requests, one for each mapping.
   * If either mapping fails then the cache can be re-populated later on with
   * stale data. In that case, please run the FSCK utility.
   * <p>
   * WARNING 2: This method will NOT delete time series data or TSMeta data 
   * associated with the UIDs. It only removes them from the UID table. Deleting
   * a metric is generally safe as you won't query over it in the future. But
   * deleting tag keys or values can cause queries to fail if they find data
   * without a corresponding name.
   * 
   * @param name The name of the UID to delete
   * @return A deferred to wait on for completion. The result will be null if
   * successful, an exception otherwise.
   * @throws NoSuchUniqueName if the UID string did not exist in storage
   * @throws IllegalStateException if the TSDB wasn't set for this UID object
   * @since 2.2
   */
  public Deferred<Object> deleteAsync(final String name) {
    if (tsdb == null) {
      throw new IllegalStateException("The TSDB is null for this UID object.");
    }
    final byte[] uid = new byte[id_width];
    final ArrayList<Deferred<Object>> deferreds = 
        new ArrayList<Deferred<Object>>(2);
    
    /** Catches errors and still cleans out the cache */
    class ErrCB implements Callback<Object, Exception> {
      @Override
      public Object call(final Exception ex) throws Exception {
        name_cache.remove(name);
        id_cache.remove(fromBytes(uid));
        LOG.error("Failed to delete " + fromBytes(kind) + " UID " + name 
            + " but still cleared the cache", ex);
        return ex;
      }
    }
    
    /** Used to wait on the group of delete requests */
    class GroupCB implements Callback<Deferred<Object>, ArrayList<Object>> {
      @Override
      public Deferred<Object> call(final ArrayList<Object> response) 
          throws Exception {
        name_cache.remove(name);
        id_cache.remove(fromBytes(uid));
        LOG.info("Successfully deleted " + fromBytes(kind) + " UID " + name);
        return Deferred.fromResult(null);
      }
    }
    
    /** Called after fetching the UID from storage */
    class LookupCB implements Callback<Deferred<Object>, byte[]> {
      @Override
      public Deferred<Object> call(final byte[] stored_uid) throws Exception {
        if (stored_uid == null) {
          return Deferred.fromError(new NoSuchUniqueName(kind(), name));
        }
        System.arraycopy(stored_uid, 0, uid, 0, id_width);
        final DeleteRequest forward = 
            new DeleteRequest(table, toBytes(name), ID_FAMILY, kind);
        deferreds.add(tsdb.getClient().delete(forward));
        
        final DeleteRequest reverse = 
            new DeleteRequest(table, uid, NAME_FAMILY, kind);
        deferreds.add(tsdb.getClient().delete(reverse));
        
        final DeleteRequest meta = new DeleteRequest(table, uid, NAME_FAMILY, 
            toBytes((type.toString().toLowerCase() + "_meta")));
        deferreds.add(tsdb.getClient().delete(meta));
        return Deferred.group(deferreds).addCallbackDeferring(new GroupCB());
      }
    }
    
    final byte[] cached_uid = name_cache.get(name);
    if (cached_uid == null) {
      return getIdFromHBase(name).addCallbackDeferring(new LookupCB())
          .addErrback(new ErrCB());
    }
    System.arraycopy(cached_uid, 0, uid, 0, id_width);
    final DeleteRequest forward = 
        new DeleteRequest(table, toBytes(name), ID_FAMILY, kind);
    deferreds.add(tsdb.getClient().delete(forward));
    
    final DeleteRequest reverse = 
        new DeleteRequest(table, uid, NAME_FAMILY, kind);
    deferreds.add(tsdb.getClient().delete(reverse));
    
    final DeleteRequest meta = new DeleteRequest(table, uid, NAME_FAMILY, 
        toBytes((type.toString().toLowerCase() + "_meta")));
    deferreds.add(tsdb.getClient().delete(meta));
    return Deferred.group(deferreds).addCallbackDeferring(new GroupCB())
        .addErrback(new ErrCB());
  }
  
  /** The start row to scan on empty search strings.  `!' = first ASCII char. */
  private static final byte[] START_ROW = new byte[] { '!' };

  /** The end row to scan on empty search strings.  `~' = last ASCII char. */
  private static final byte[] END_ROW = new byte[] { '~' };

  /**
   * Creates a scanner that scans the right range of rows for suggestions.
   * @param client The HBase client to use.
   * @param tsd_uid_table Table where IDs are stored.
   * @param search The string to start searching at
   * @param kind_or_null The kind of UID to search or null for any kinds.
   * @param max_results The max number of results to return
   */
  private static Scanner getSuggestScanner(final HBaseClient client,
      final byte[] tsd_uid_table, final String search,
      final byte[] kind_or_null, final int max_results) {
    final byte[] start_row;
    final byte[] end_row;
    if (search.isEmpty()) {
      start_row = START_ROW;
      end_row = END_ROW;
    } else {
      start_row = toBytes(search);
      end_row = Arrays.copyOf(start_row, start_row.length);
      end_row[start_row.length - 1]++;
    }
    final Scanner scanner = client.newScanner(tsd_uid_table);
    scanner.setStartKey(start_row);
    scanner.setStopKey(end_row);
    scanner.setFamily(ID_FAMILY);
    if (kind_or_null != null) {
      scanner.setQualifier(kind_or_null);
    }
    scanner.setMaxNumRows(max_results <= 4096 ? max_results : 4096);
    return scanner;
  }

  /**
   * 1.Returns the cell of the specified row key, using family:kind.
   * 返回指定行键的具体单元值，使用的列族是kind【想想为什么是kind？】
   *
   * 2.hbaseGet()方法返回的是一个Deferred<byte[]>对象，因为是异步过程
   * 3.
   * */
  private Deferred<byte[]> hbaseGet(final byte[] key, final byte[] family) {

      //Get Request Reads something from HBase
      final GetRequest get = new GetRequest(table, key);
    get.family(family).qualifier(kind);
    class GetCB implements Callback<byte[], ArrayList<KeyValue>> {
      public byte[] call(final ArrayList<KeyValue> row) {
        if (row == null || row.isEmpty()) {
          return null;
        }
        return row.get(0).value();
      }
    }

    return client.get(get).addCallback(new GetCB());
  }

  /**
   * Attempts to run the PutRequest given in argument, retrying if needed.
   *
   * Puts are synchronized.
   *
   * @param put The PutRequest to execute.
   * @param attempts The maximum number of attempts.
   * @param wait The initial amount of time in ms to sleep for after a
   * failure.  This amount is doubled after each failed attempt.
   * @throws HBaseException if all the attempts have failed.  This exception
   * will be the exception of the last attempt.
   */
  private void hbasePutWithRetry(final PutRequest put, short attempts, short wait)
    throws HBaseException {
    put.setBufferable(false);  // TODO(tsuna): Remove once this code is async.
    while (attempts-- > 0) {
      try {
        client.put(put).joinUninterruptibly();
        return;
      } catch (HBaseException e) {
        if (attempts > 0) {
          LOG.error("Put failed, attempts left=" + attempts
                    + " (retrying in " + wait + " ms), put=" + put, e);
          try {
            Thread.sleep(wait);
          } catch (InterruptedException ie) {
            throw new RuntimeException("interrupted", ie);
          }
          wait *= 2;
        } else {
          throw e;
        }
      } catch (Exception e) {
        LOG.error("WTF?  Unexpected exception type, put=" + put, e);
      }
    }
    throw new IllegalStateException("This code should never be reached!");
  }

  private static byte[] toBytes(final String s) {
    return s.getBytes(CHARSET);
  }

  //从字节数组返回一个String
  private static String fromBytes(final byte[] b) {
    return new String(b, CHARSET);//根据指定的CHARSET构建一个String
  }

  /** Returns a human readable string representation of the object. */
  public String toString() {
    return "UniqueId(" + fromBytes(table) + ", " + kind() + ", " + id_width + ")";
  }

  /**
   * Converts a byte array to a hex encoded, upper case string with padding
   * @param uid The ID to convert
   * @return the UID as a hex string
   * @throws NullPointerException if the ID was null
   * @since 2.0
   */
  public static String uidToString(final byte[] uid) {
    return DatatypeConverter.printHexBinary(uid);
  }
  
  /**
   * Converts a hex string to a byte array
   * If the {@code uid} is less than {@code uid_length * 2} characters wide, it
   * will be padded with 0s to conform to the spec. E.g. if the tagk width is 3
   * and the given {@code uid} string is "1", the string will be padded to 
   * "000001" and then converted to a byte array to reach 3 bytes. 
   * All {@code uid}s are padded to 1 byte. If given "1", and {@code uid_length}
   * is 0, the uid will be padded to "01" then converted.
   * @param uid The UID to convert
   * @return The UID as a byte array
   * @throws NullPointerException if the ID was null
   * @throws IllegalArgumentException if the string is not valid hex
   * @since 2.0
   */
  public static byte[] stringToUid(final String uid) {
    return stringToUid(uid, (short)0);
  }

  /**
   * Converts a UID to an integer value. The array must be the same length as
   * uid_length or an exception will be thrown.
   * @param uid The hex encoded UID to convert
   * @param uid_length Length the array SHOULD be according to the UID config
   * @return The UID converted to an integer
   * @throws IllegalArgumentException if the length of the byte array does not
   * match the uid_length value
   * @since 2.1
   */
  public static long uidToLong(final String uid, final short uid_length) {
    return uidToLong(stringToUid(uid), uid_length);
  }
  
  /**
   * Converts a UID to an integer value. The array must be the same length as
   * uid_length or an exception will be thrown.
   * @param uid The byte array to convert
   * @param uid_length Length the array SHOULD be according to the UID config
   * @return The UID converted to an integer
   * @throws IllegalArgumentException if the length of the byte array does not
   * match the uid_length value
   * @since 2.1
   */
  public static long uidToLong(final byte[] uid, final short uid_length) {
    if (uid.length != uid_length) {
      throw new IllegalArgumentException("UID was " + uid.length 
          + " bytes long but expected to be " + uid_length);
    }
    
    final byte[] uid_raw = new byte[8];
    System.arraycopy(uid, 0, uid_raw, 8 - uid_length, uid_length);
    return Bytes.getLong(uid_raw);
  }
 
  /**
   * Converts a Long to a byte array with the proper UID width
   * @param uid The UID to convert
   * @param width The width of the UID in bytes
   * @return The UID as a byte array
   * @throws IllegalStateException if the UID is larger than the width would
   * allow
   * @since 2.1
   */
  public static byte[] longToUID(final long uid, final short width) {
    // Verify that we're going to drop bytes that are 0.
    final byte[] padded = Bytes.fromLong(uid);
    for (int i = 0; i < padded.length - width; i++) {
      if (padded[i] != 0) {
        final String message = "UID " + Long.toString(uid) + 
          " was too large for " + width + " bytes";
        LOG.error("OMG " + message);
        throw new IllegalStateException(message);
      }
    }
    // Shrink the ID on the requested number of bytes.
    return Arrays.copyOfRange(padded, padded.length - width, padded.length);
  }
  
  /**
   * Appends the given UID to the given string buffer, followed by "\\E".
   * @param buf The buffer to append
   * @param id The UID to add as a binary regex pattern
   * @since 2.1
   */
  public static void addIdToRegexp(final StringBuilder buf, final byte[] id) {
    boolean backslash = false;
    for (final byte b : id) {
      buf.append((char) (b & 0xFF));
      if (b == 'E' && backslash) {  // If we saw a `\' and now we have a `E'.
        // So we just terminated the quoted section because we just added \E
        // to `buf'.  So let's put a literal \E now and start quoting again.
        buf.append("\\\\E\\Q");
      } else {
        backslash = b == '\\';
      }
    }
    buf.append("\\E");
  }
  
  /**
   * Attempts to convert the given string to a type enumerator
   * @param type The string to convert
   * @return a valid UniqueIdType if matched
   * @throws IllegalArgumentException if the string did not match a type
   * @since 2.0
   */
  public static UniqueIdType stringToUniqueIdType(final String type) {
    if (type.toLowerCase().equals("metric") || 
        type.toLowerCase().equals("metrics")) {
      return UniqueIdType.METRIC;
    } else if (type.toLowerCase().equals("tagk")) {
      return UniqueIdType.TAGK;
    } else if (type.toLowerCase().equals("tagv")) {
      return UniqueIdType.TAGV;
    } else {
      throw new IllegalArgumentException("Invalid type requested: " + type);
    }
  }
  
  /**
   * Converts a hex string to a byte array
   * If the {@code uid} is less than {@code uid_length * 2} characters wide, it
   * will be padded with 0s to conform to the spec. E.g. if the tagk width is 3
   * and the given {@code uid} string is "1", the string will be padded to 
   * "000001" and then converted to a byte array to reach 3 bytes. 
   * All {@code uid}s are padded to 1 byte. If given "1", and {@code uid_length}
   * is 0, the uid will be padded to "01" then converted.
   * @param uid The UID to convert
   * @param uid_length An optional length, in bytes, that the UID must conform
   * to. Set to 0 if not used.
   * @return The UID as a byte array
   * @throws NullPointerException if the ID was null
   * @throws IllegalArgumentException if the string is not valid hex
   * @since 2.0
   */
  public static byte[] stringToUid(final String uid, final short uid_length) {
    if (uid == null || uid.isEmpty()) {
      throw new IllegalArgumentException("UID was empty");
    }
    String id = uid;
    if (uid_length > 0) {
      while (id.length() < uid_length * 2) {
        id = "0" + id;
      }
    } else {
      if (id.length() % 2 > 0) {
        id = "0" + id;
      }
    }
    return DatatypeConverter.parseHexBinary(id);
  }

  /**
   * Extracts the TSUID from a storage row key that includes the timestamp.
   * @param row_key The row key to process
   * @param metric_width The width of the metric
   * @param timestamp_width The width of the timestamp
   * @return The TSUID as a byte array
   * @throws IllegalArgumentException if the row key is missing tags or it is
   * corrupt such as a salted key when salting is disabled or vice versa.
   */
  public static byte[] getTSUIDFromKey(final byte[] row_key, 
      final short metric_width, final short timestamp_width) {
    int idx = 0;
    // validation
    final int tag_pair_width = TSDB.tagk_width() + TSDB.tagv_width();
    final int tags_length = row_key.length - 
        (Const.SALT_WIDTH() + metric_width + timestamp_width);
    if (tags_length < tag_pair_width || (tags_length % tag_pair_width) != 0) {
      throw new IllegalArgumentException(
          "Row key is missing tags or it is corrupted " + Arrays.toString(row_key));
    }
    final byte[] tsuid = new byte[
                 row_key.length - timestamp_width - Const.SALT_WIDTH()];
    for (int i = Const.SALT_WIDTH(); i < row_key.length; i++) {
      if (i < Const.SALT_WIDTH() + metric_width || 
          i >= (Const.SALT_WIDTH() + metric_width + timestamp_width)) {
        tsuid[idx] = row_key[i];
        idx++;
      }
    }
    return tsuid;
  }
  
  /**
   * Extracts a list of tagks and tagvs as individual values in a list
   * @param tsuid The tsuid to parse
   * @return A list of tagk/tagv UIDs alternating with tagk, tagv, tagk, tagv
   * @throws IllegalArgumentException if the TSUID is malformed
   * @since 2.1
   */
  public static List<byte[]> getTagsFromTSUID(final String tsuid) {
    if (tsuid == null || tsuid.isEmpty()) {
      throw new IllegalArgumentException("Missing TSUID");
    }
    if (tsuid.length() <= TSDB.metrics_width() * 2) {
      throw new IllegalArgumentException(
          "TSUID is too short, may be missing tags");
    }
     
    final List<byte[]> tags = new ArrayList<byte[]>();
    final int pair_width = (TSDB.tagk_width() * 2) + (TSDB.tagv_width() * 2);
    
    // start after the metric then iterate over each tagk/tagv pair
    for (int i = TSDB.metrics_width() * 2; i < tsuid.length(); i+= pair_width) {
      if (i + pair_width > tsuid.length()){
        throw new IllegalArgumentException(
            "The TSUID appears to be malformed, improper tag width");
      }
      String tag = tsuid.substring(i, i + (TSDB.tagk_width() * 2));
      tags.add(UniqueId.stringToUid(tag));
      tag = tsuid.substring(i + (TSDB.tagk_width() * 2), i + pair_width);
      tags.add(UniqueId.stringToUid(tag));
    }
    return tags;
  }
   
  /**
   * Extracts a list of tagk/tagv pairs from a tsuid
   * @param tsuid The tsuid to parse
   * @return A list of tagk/tagv UID pairs
   * @throws IllegalArgumentException if the TSUID is malformed
   * @since 2.0
   */
  public static List<byte[]> getTagPairsFromTSUID(final String tsuid) {
     if (tsuid == null || tsuid.isEmpty()) {
       throw new IllegalArgumentException("Missing TSUID");
     }
     if (tsuid.length() <= TSDB.metrics_width() * 2) {
       throw new IllegalArgumentException(
           "TSUID is too short, may be missing tags");
     }
      
     final List<byte[]> tags = new ArrayList<byte[]>();
     final int pair_width = (TSDB.tagk_width() * 2) + (TSDB.tagv_width() * 2);
     
     // start after the metric then iterate over each tagk/tagv pair
     for (int i = TSDB.metrics_width() * 2; i < tsuid.length(); i+= pair_width) {
       if (i + pair_width > tsuid.length()){
         throw new IllegalArgumentException(
             "The TSUID appears to be malformed, improper tag width");
       }
       String tag = tsuid.substring(i, i + pair_width);
       tags.add(UniqueId.stringToUid(tag));
     }
     return tags;
   }
  
  /**
   * Extracts a list of tagk/tagv pairs from a tsuid
   * @param tsuid The tsuid to parse
   * @return A list of tagk/tagv UID pairs
   * @throws IllegalArgumentException if the TSUID is malformed
   * @since 2.0
   */
  public static List<byte[]> getTagPairsFromTSUID(final byte[] tsuid) {
    if (tsuid == null) {
      throw new IllegalArgumentException("Missing TSUID");
    }
    if (tsuid.length <= TSDB.metrics_width()) {
      throw new IllegalArgumentException(
          "TSUID is too short, may be missing tags");
    }
     
    final List<byte[]> tags = new ArrayList<byte[]>();
    final int pair_width = TSDB.tagk_width() + TSDB.tagv_width();
    
    // start after the metric then iterate over each tagk/tagv pair
    for (int i = TSDB.metrics_width(); i < tsuid.length; i+= pair_width) {
      if (i + pair_width > tsuid.length){
        throw new IllegalArgumentException(
            "The TSUID appears to be malformed, improper tag width");
      }
      tags.add(Arrays.copyOfRange(tsuid, i, i + pair_width));
    }
    return tags;
  }
  
  /**
   * Returns a map of max UIDs from storage for the given list of UID types 
   * @param tsdb The TSDB to which we belong
   * @param kinds A list of qualifiers to fetch
   * @return A map with the "kind" as the key and the maximum assigned UID as
   * the value
   * @since 2.0
   */
  public static Deferred<Map<String, Long>> getUsedUIDs(final TSDB tsdb,
      final byte[][] kinds) {
    
    /**
     * Returns a map with 0 if the max ID row hasn't been initialized yet, 
     * otherwise the map has actual data
     */
    final class GetCB implements Callback<Map<String, Long>, 
      ArrayList<KeyValue>> {

      @Override
      public Map<String, Long> call(final ArrayList<KeyValue> row)
          throws Exception {
        
        final Map<String, Long> results = new HashMap<String, Long>(3);
        if (row == null || row.isEmpty()) {
          // it could be the case that this is the first time the TSD has run
          // and the user hasn't put any metrics in, so log and return 0s
          LOG.info("Could not find the UID assignment row");
          for (final byte[] kind : kinds) {
            results.put(new String(kind, CHARSET), 0L);
          }
          return results;
        }
        
        for (final KeyValue column : row) {
          results.put(new String(column.qualifier(), CHARSET), 
              Bytes.getLong(column.value()));
        }
        
        // if the user is starting with a fresh UID table, we need to account
        // for missing columns
        for (final byte[] kind : kinds) {
          if (results.get(new String(kind, CHARSET)) == null) {
            results.put(new String(kind, CHARSET), 0L);
          }
        }
        return results;
      }
      
    }
    
    final GetRequest get = new GetRequest(tsdb.uidTable(), MAXID_ROW);
    get.family(ID_FAMILY);
    get.qualifiers(kinds);
    return tsdb.getClient().get(get).addCallback(new GetCB());
  }

  /**
   * Pre-load UID caches, scanning up to "tsd.core.preload_uid_cache.max_entries"
   * rows from the UID table.
   * @param tsdb The TSDB to use 
   * @param uid_cache_map A map of {@link UniqueId} objects keyed on the kind.
   * @throws HBaseException Passes any HBaseException from HBase scanner.
   * @throws RuntimeException Wraps any non HBaseException from HBase scanner.
   * @2.1
   */
  public static void preloadUidCache(final TSDB tsdb,
      final ByteMap<UniqueId> uid_cache_map) throws HBaseException {
    int max_results = tsdb.getConfig().getInt(
        "tsd.core.preload_uid_cache.max_entries");
    LOG.info("Preloading uid cache with max_results=" + max_results);
    if (max_results <= 0) {
      return;
    }
    Scanner scanner = null;
    try {
      int num_rows = 0;
      scanner = getSuggestScanner(tsdb.getClient(), tsdb.uidTable(), "", null, 
          max_results);
      for (ArrayList<ArrayList<KeyValue>> rows = scanner.nextRows().join();
          rows != null;
          rows = scanner.nextRows().join()) {
        for (final ArrayList<KeyValue> row : rows) {
          for (KeyValue kv: row) {
            final String name = fromBytes(kv.key());
            final byte[] kind = kv.qualifier();
            final byte[] id = kv.value();
            LOG.debug("id='{}', name='{}', kind='{}'", Arrays.toString(id),
                name, fromBytes(kind));
            UniqueId uid_cache = uid_cache_map.get(kind);
            if (uid_cache != null) {
              uid_cache.cacheMapping(name, id);
            }
          }
          num_rows += row.size();
          row.clear();  // free()
          if (num_rows >= max_results) {
            break;
          }
        }
      }
      for (UniqueId unique_id_table : uid_cache_map.values()) {
        LOG.info("After preloading, uid cache '{}' has {} ids and {} names.",
                 unique_id_table.kind(),
                 unique_id_table.id_cache.size(),
                 unique_id_table.name_cache.size());
      }
    } catch (Exception e) {
      if (e instanceof HBaseException) {
        throw (HBaseException)e;
      } else if (e instanceof RuntimeException) {
        throw (RuntimeException)e;
      } else {
        throw new RuntimeException("Error while preloading IDs", e);
      }
    } finally {
      if (scanner != null) {
        scanner.close();
      }
    }
  }
}
