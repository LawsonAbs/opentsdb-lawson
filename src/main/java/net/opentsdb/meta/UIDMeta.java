// This file is part of OpenTSDB.
// Copyright (C) 2013  The OpenTSDB Authors.
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
package net.opentsdb.meta;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.hbase.async.DeleteRequest;
import org.hbase.async.GetRequest;
import org.hbase.async.HBaseException;
import org.hbase.async.KeyValue;
import org.hbase.async.PutRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.stumbleupon.async.Callback;
import com.stumbleupon.async.Deferred;

import net.opentsdb.core.TSDB;
import net.opentsdb.uid.UniqueId;
import net.opentsdb.uid.UniqueId.UniqueIdType;
import net.opentsdb.utils.JSON;
import net.opentsdb.utils.JSONException;

/**
 * UIDMeta objects are associated with the UniqueId of metrics, tag names
 * or tag values. When a new metric, tagk or tagv is generated, a UIDMeta object
 * will also be written to storage with only the uid, type and name filled out. 
 *
 * UIDMeta对象是与 metrics, tag names, tag values相应的UniqueId（唯一Id）。当一个新的metric，tag k或者tag v
 * 产生出来，同时一个UIDmeta对象也将被写入存储，仅仅uid，type，name填满。【翻译不好】
 *
 * <p>
 * Users are allowed to edit the following fields:
 * <ul><li>display_name</li>
 * <li>description</li>
 * <li>notes</li>
 * <li>custom</li></ul>
 * The {@code name}, {@code uid}, {@code type} and {@code created} fields can 
 * only be modified by the system and are usually done so on object creation.
 * <p>
    用户可以修改的字段如下：
     display_name
*     description
*     notes
*    custom
*   name,uid,type,以及创建的字段仅能被系统修改，这个步骤经常是在对象创建时完成的。 *
 *
 *
 * When you call {@link #syncToStorage} on this object, it will verify that the
 * UID object this meta data is linked with still exists. Then it will fetch the 
 * existing data and copy changes, overwriting the user fields if specific 
 * (e.g. via a PUT command). If overwriting is not called for (e.g. a POST was 
 * issued), then only the fields provided by the user will be saved, preserving 
 * all of the other fields in storage. Hence the need for the {@code changed} 
 * hash map and the {@link #syncMeta} method.
 *
 * 当你调用这个对象的syncTostorage方法时，它将会验证这个元数据所对应的UID对象是否仍然存在。
 * 然后它将会寻找存在的元数据并且加载修改，如果有指定的话，会覆写用户字段（通过一个put命令）。
 * 如果覆写未被调用（例如，一个post被调用）。那么仅仅被用户提供的字段的将会被存储，保留所有
 * 其它的字段。因为变化的hash map（这个changed hash map后面需要使用到）以及syncMeta方法需要。
 *
 * <p>
 * Note that the HBase specific storage code will be removed once we have a DAL
 * 注意HBase 指定存储代码将会在DAL（what's DAL?）功能实现之后移除。
 * @since 2.0
 *
 */
@JsonIgnoreProperties(ignoreUnknown = true) 
@JsonAutoDetect(fieldVisibility = Visibility.PUBLIC_ONLY)
/*
1.final 修饰的类，不可更改
 */
public final class UIDMeta {
  private static final Logger LOG = LoggerFactory.getLogger(UIDMeta.class);
  
  /** Charset used to convert Strings to byte arrays and back.
   * 用于转换和返回字符数组的字符集
   * */
  private static final Charset CHARSET = Charset.forName("ISO-8859-1");
  
  /** The single column family used by this class.
   *
   * 这个类使用的唯一列族
   * */
  private static final byte[] FAMILY = "name".getBytes(CHARSET);



  /** A hexadecimal representation of the UID this metadata is associated with
   * 与这个metadata相联系的UID的十六进制表示
   * */
  private String uid = "";
  
  /** The type of UID this metadata represents
   * 这个metadata表示的UID类型
   * 对这个type其实不大懂？？？？？？？？？？？？？？？？？？？？
   * */
  @JsonDeserialize(using = JSON.UniqueIdTypeDeserializer.class)
  private UniqueIdType type = null;
  
  /** 
   * This is the identical name of what is stored in the UID table
   * It cannot be overridden 
   */
  private String name = "";
  
  /** 
   * An optional, user supplied name used for display purposes only
   * If this field is empty, the {@link name} field should be used
   */
  private String display_name = "";
  
  /** A short description of what this object represents */
  private String description = "";
  
  /** Optional, detailed notes about what the object represents */
  private String notes = "";
  
  /** A timestamp of when this UID was first recorded by OpenTSDB in seconds */
  private long created = 0;
  
  /** Optional user supplied key/values */
  private HashMap<String, String> custom = null;
  
  /** Tracks fields that have changed by the user to avoid overwrites
   * 跟踪被用户修改的字段避免覆写
   * */
  private final HashMap<String, Boolean> changed = 
    new HashMap<String, Boolean>();
  
  /**
   * Default constructor
   * Initializes the the changed map
   */
  public UIDMeta() {
    initializeChangedMap();
  }
 
  /**
   * Constructor used for overwriting. Will not reset the name or created values
   * in storage.
   * 用于覆写的构造器。 不会重置存储中的name或是创建的值
   * @param type Type of UID object
   * @param uid UID of the object
   *
   * 01.UniqueIdType 是一UniqueId的类型，这是一个enum对象，目前有三种：    METRIC,  TAGK,  TAGV
   */
  public UIDMeta(final UniqueIdType type, final String uid) {
    this.type = type;
    this.uid = uid;
    initializeChangedMap();
  }
  
  /**
   * Constructor used by TSD only to create a new UID with the given data and 
   * the current system time for {@code created}
   * 使用给定的数据已经目前创建的系统时间，被TSD用于创建一个新的UID的构造器
   *
   * @param type Type of UID object  UID对象的Type
   * @param uid UID of the object   对象的uid
   * @param name Name of the UID    UID的name
   */
  public UIDMeta(final UniqueIdType type, final byte[] uid, final String name) {
    this.type = type;
    this.uid = UniqueId.uidToString(uid);
    this.name = name;
    created = System.currentTimeMillis() / 1000;  //=> current system time for created
    initializeChangedMap();
    changed.put("created", true);  // 将created 置为true
  }
  
  /** @return a string with details about this object
   * 重写toString()方法*/
  @Override
  public String toString() {
    return "'" + type.toString() + ":" + uid + "'";
  }
  
  /**
   * Attempts a CompareAndSet storage call, loading the object from storage, 
   * synchronizing changes, and attempting a put.
   * 企图进行一个CompareAndSet（比较且设置，简写为CAS）的存储调用，从存储中加载对象，异步改变，最后
   * 尝试进行put操作
   *
   * <b>Note:</b> If the local object didn't have any fields set by the caller
   * then the data will not be written.
   * 如果本地的对象没有任何由调用者设置的字段，那么数据不会被写入。
   *
   *
   * @param tsdb The TSDB to use for storage access
   * @param overwrite When the RPC method is PUT, will overwrite all user
   * accessible fields
   * @return True if the storage call was successful, false if the object was
   * modified in storage during the CAS call. If false, retry the call. Other 
   * failures will result in an exception being thrown.
   * 如果存储过程成功，返回为真；如果对象在CAS调用过程中有所修改，则为false。如果为false，重新调用。
   * 其它的失败将会导致抛出一个异常
   *
   * @throws HBaseException if there was an issue fetching
   * @throws IllegalArgumentException if parsing failed
   * @throws NoSuchUniqueId If the UID does not exist
   * @throws IllegalStateException if the data hasn't changed. This is OK!
   * @throws JSONException if the object could not be serialized
   */
  public Deferred<Boolean> syncToStorage(final TSDB tsdb, final boolean overwrite) {
      //需要注意这里uid报错的条件
    if (uid == null || uid.isEmpty()) {
      throw new IllegalArgumentException("Missing UID");
    }
    if (type == null) {
      throw new IllegalArgumentException("Missing type");
    }

    boolean has_changes = false;
    //entrySet()方法是将map转换成set.
    for (Map.Entry<String, Boolean> entry : changed.entrySet()) {
        //若有修改，则跳出循环
        if (entry.getValue()) {
        has_changes = true;
        break;
      }
    }
    if (!has_changes) {
      LOG.debug(this + " does not have changes, skipping sync to storage");
      throw new IllegalStateException("No changes detected in UID meta data");
    }

    /**
     * Callback used to verify that the UID to name mapping exists. Uses the TSD
     * for verification so the name may be cached. If the name does not exist
     * it will throw a NoSuchUniqueId and the meta data will not be saved to
     * storage
     * 回调去验证UID->name映射已经存在。使用TSD去验证消息，所以名字可能会被缓存，如果名字不存在，
     * 将会抛出一个NoSuchUniqueId，并且元数据不会存储
     *
     *
     *
     * Callback接口：
     * 1.Callback(Deferred<Boolean>,String)这是一个只有参数的接口
     * 2.回调函数通常是作为匿名函数调用的。
     * 3.建议实现toString()方法，这样做的目的是方便调试，知道callback的每个步骤都实现了什么。
     * 4.如果你使用debug模式，且你使用Deferred模式，这将会对你理解callback链大有帮助
     *
     *
     * 1.这是一个内部类（在方法中）。实现了Callback接口
     * 2.注意内部类中还有一个内部类。而且这两个内部类都实现了Callback()方法。
     */
    final class NameCB implements Callback<Deferred<Boolean>, String> {
      private final UIDMeta local_meta;
      
      public NameCB(final UIDMeta meta) {
        local_meta = meta;
      }
      
      /**
       *  Nested callback used to merge and store the meta data after verifying
       *  that the UID mapping exists. It has to access the {@code local_meta} 
       *  object so that's why it's nested within the NameCB class
       *  在验证UID映射存在时，内嵌的callback去合并且存储元数据。
       *  它（类StoreUIDMeta）必须访问local_meta对象，这就是为什么它在NameCB类中的原因。
       */
      final class StoreUIDMeta implements Callback<Deferred<Boolean>, 
        ArrayList<KeyValue>> {

        /**
         * Executes the CompareAndSet after merging changes
         * 在合并操作之后，执行CompareAndSet操作
         *
         * @return True if the CAS was successful, false if the stored data
         * was modified during flight.
         * 如果CAS操作成功，则返回true；如果存储的数据在传递过程中被修改则为false。
         *
         * 1.覆写call方法，这里的参数是ArrayList<KeyValue>类型的row
         */
        @Override
        public Deferred<Boolean> call(final ArrayList<KeyValue> row) 
          throws Exception {
          
          final UIDMeta stored_meta;
          if (row == null || row.isEmpty()) {
            stored_meta = null;
          } else {
            //这里的UIDMeta是目标类，初始的字节数组是：row.get(0).value()
              //需要研究一下KeyValue类
            stored_meta = JSON.parseToObject(row.get(0).value(), UIDMeta.class);

            //为什么在这里调用？--> initializeChangedMap()方法
            stored_meta.initializeChangedMap();
          }


          final byte[] original_meta = row == null || row.isEmpty() ? 
              new byte[0] : row.get(0).value();

          if (stored_meta != null) {
            local_meta.syncMeta(stored_meta, overwrite);
          }
       
          // verify the name is set locally just to be safe
          if (name == null || name.isEmpty()) {
            local_meta.name = name;
          }

            /**
             * 1.PutRequest()构造器使用当前的时间
             * 2.
             */
            final PutRequest put = new PutRequest(tsdb.uidTable(),
              UniqueId.stringToUid(uid), FAMILY, 
              (type.toString().toLowerCase() + "_meta").getBytes(CHARSET), 
              local_meta.getStorageJSON());
          return tsdb.getClient().compareAndSet(put, original_meta);
        }
      }
      
      /**
       * NameCB method that fetches the object from storage for merging and
       * use in the CAS call
       * 从存储中获取NameCB方法是为了合并操作，以及使用CAS(compare and set)调用？
       *
       * @return The results of the {@link #StoreUIDMeta} callback
       */
      @Override
      public Deferred<Boolean> call(final String name) throws Exception {
        
        final GetRequest get = new GetRequest(tsdb.uidTable(), 
            UniqueId.stringToUid(uid));
        get.family(FAMILY);
        get.qualifier((type.toString().toLowerCase() + "_meta").getBytes(CHARSET));
        
        // #2 deferred
        return tsdb.getClient().get(get)
          .addCallbackDeferring(new StoreUIDMeta());
      }
      
    }

    // start the callback chain by veryfing that the UID name mapping exists
    return tsdb.getUidName(type, UniqueId.stringToUid(uid))
      .addCallbackDeferring(new NameCB(this));
  }
  
  /**
   * Attempts to store a blank, new UID meta object in the proper location.
   * 尝试在适合的位置存储一个空的，新的UID meta对象。
   *
   *
   * <b>Warning:</b> This should not be called by user accessible methods as it
   * will overwrite any data already in the column. This method does not use 
   * a CAS, instead it uses a PUT to overwrite anything in the column.
   * 警告：这个方法不应该被任何用户调用，因为这个方法将会覆写列中的数据。这个方法不会使用CAS，相反，
   * 它使用一个put操作去覆写列中的任何值。
   *
   * @param tsdb The TSDB to use for calls
   * @return A deferred without meaning. The response may be null and should
   * only be used to track completion.
   * @throws HBaseException if there was an issue writing to storage
   * @throws IllegalArgumentException if data was missing
   * @throws JSONException if the object could not be serialized
   */
  public Deferred<Object> storeNew(final TSDB tsdb) {
    if (uid == null || uid.isEmpty()) {
      throw new IllegalArgumentException("Missing UID");
    }
    if (type == null) {
      throw new IllegalArgumentException("Missing type");
    }
    if (name == null || name.isEmpty()) {
      throw new IllegalArgumentException("Missing name");
    }

    final PutRequest put = new PutRequest(tsdb.uidTable(), 
        UniqueId.stringToUid(uid), FAMILY, 
        (type.toString().toLowerCase() + "_meta").getBytes(CHARSET), 
        UIDMeta.this.getStorageJSON());
    return tsdb.getClient().put(put);
  }
  
  /**
   * Attempts to delete the meta object from storage
   * @param tsdb The TSDB to use for access to storage
   * @return A deferred without meaning. The response may be null and should
   * only be used to track completion.
   * @throws HBaseException if there was an issue
   * @throws IllegalArgumentException if data was missing (uid and type)
   */
  public Deferred<Object> delete(final TSDB tsdb) {
    if (uid == null || uid.isEmpty()) {
      throw new IllegalArgumentException("Missing UID");
    }
    if (type == null) {
      throw new IllegalArgumentException("Missing type");
    }

    final DeleteRequest delete = new DeleteRequest(tsdb.uidTable(), 
        UniqueId.stringToUid(uid), FAMILY, 
        (type.toString().toLowerCase() + "_meta").getBytes(CHARSET));
    return tsdb.getClient().delete(delete);
  }
  
  /**
   * Convenience overload of {@code getUIDMeta(TSDB, UniqueIdType, byte[])}
   * @param tsdb The TSDB to use for storage access
   * @param type The type of UID to fetch
   * @param uid The ID of the meta to fetch
   * @return A UIDMeta from storage or a default
   * @throws HBaseException if there was an issue fetching
   * @throws NoSuchUniqueId If the UID does not exist
   */
  public static Deferred<UIDMeta> getUIDMeta(final TSDB tsdb, 
      final UniqueIdType type, final String uid) {
    return getUIDMeta(tsdb, type, UniqueId.stringToUid(uid));
  }
  
  /**
   * Verifies the UID object exists, then attempts to fetch the meta from 
   * storage and if not found, returns a default object.
   * <p>
   * The reason for returning a default object (with the type, uid and name set)
   * is due to users who may have just enabled meta data or have upgraded; we 
   * want to return valid data. If they modify the entry, it will write to 
   * storage. You can tell it's a default if the {@code created} value is 0. If
   * the meta was generated at UID assignment or updated by the meta sync CLI
   * command, it will have a valid created timestamp.
   * @param tsdb The TSDB to use for storage access
   * @param type The type of UID to fetch
   * @param uid The ID of the meta to fetch
   * @return A UIDMeta from storage or a default
   * @throws HBaseException if there was an issue fetching
   * @throws NoSuchUniqueId If the UID does not exist
   */
  public static Deferred<UIDMeta> getUIDMeta(final TSDB tsdb, 
      final UniqueIdType type, final byte[] uid) {
    
    /**
     * Callback used to verify that the UID to name mapping exists. Uses the TSD
     * for verification so the name may be cached. If the name does not exist
     * it will throw a NoSuchUniqueId and the meta data will not be returned. 
     * This helps in case the user deletes a UID but the meta data is still 
     * stored. The fsck utility can be used later to cleanup orphaned objects.
     */
    class NameCB implements Callback<Deferred<UIDMeta>, String> {

      /**
       * Called after verifying that the name mapping exists
       * @return The results of {@link #FetchMetaCB}
       */
      @Override
      public Deferred<UIDMeta> call(final String name) throws Exception {
        
        /**
         * Inner class called to retrieve the meta data after verifying that the
         * name mapping exists. It requires the name to set the default, hence
         * the reason it's nested.
         */
        class FetchMetaCB implements Callback<Deferred<UIDMeta>, 
          ArrayList<KeyValue>> {
  
          /**
           * Called to parse the response of our storage GET call after 
           * verification
           * @return The stored UIDMeta or a default object if the meta data
           * did not exist 
           */
          @Override
          public Deferred<UIDMeta> call(ArrayList<KeyValue> row) 
            throws Exception {
            
            if (row == null || row.isEmpty()) {
              // return the default
              final UIDMeta meta = new UIDMeta();
              meta.uid = UniqueId.uidToString(uid);
              meta.type = type;
              meta.name = name;
              return Deferred.fromResult(meta);
            }
            final UIDMeta meta = JSON.parseToObject(row.get(0).value(), 
                UIDMeta.class);
            
            // overwrite the name and UID
            meta.name = name;
            meta.uid = UniqueId.uidToString(uid);
            
            // fix missing types
            if (meta.type == null) {
              final String qualifier = 
                new String(row.get(0).qualifier(), CHARSET);
              meta.type = UniqueId.stringToUniqueIdType(qualifier.substring(0, 
                  qualifier.indexOf("_meta")));
            }
            meta.initializeChangedMap();
            return Deferred.fromResult(meta);
          }
          
        }
        
        final GetRequest get = new GetRequest(tsdb.uidTable(), uid);
        get.family(FAMILY);
        get.qualifier((type.toString().toLowerCase() + "_meta").getBytes(CHARSET));
        return tsdb.getClient().get(get).addCallbackDeferring(new FetchMetaCB());
      }
    }
    
    // verify that the UID is still in the map before fetching from storage
    return tsdb.getUidName(type, uid).addCallbackDeferring(new NameCB());
  }
    
  /**
   * Syncs the local object with the stored object for atomic writes, 
   * overwriting the stored data if the user issued a PUT request
   * <b>Note:</b> This method also resets the {@code changed} map to false
   * for every field
   * @param meta The stored object to sync from
   * @param overwrite Whether or not all user mutable data in storage should be
   * replaced by the local object
   */
  private void syncMeta(final UIDMeta meta, final boolean overwrite) {
    // copy non-user-accessible data first
    if (meta.uid != null && !meta.uid.isEmpty()) {
      uid = meta.uid;
    }
    if (meta.name != null && !meta.name.isEmpty()) {
      name = meta.name;
    }
    if (meta.type != null) {
      type = meta.type;
    }
    if (meta.created > 0 && (meta.created < created || created == 0)) {
      created = meta.created;
    }
    
    // handle user-accessible stuff
    if (!overwrite && !changed.get("display_name")) {
      display_name = meta.display_name;
    }
    if (!overwrite && !changed.get("description")) {
      description = meta.description;
    }
    if (!overwrite && !changed.get("notes")) {
      notes = meta.notes;
    }
    if (!overwrite && !changed.get("custom")) {
      custom = meta.custom;
    }

    // reset changed flags
    initializeChangedMap();
  }
  
  /**
   * Sets or resets the changed map flags
   */
  private void initializeChangedMap() {
    // set changed flags
    changed.put("display_name", false);
    changed.put("description", false);
    changed.put("notes", false);
    changed.put("custom", false);
    changed.put("created", false); 
  }
  
  /**
   * Formats the JSON output for writing to storage. It drops objects we don't
   * need or want to store (such as the UIDMeta objects or the total dps) to
   * save space. It also serializes in order so that we can make a proper CAS
   * call. Otherwise the POJO serializer may place the fields in any order
   * and CAS calls would fail all the time.
   * @return A byte array to write to storage
   */
  private byte[] getStorageJSON() {
    // 256 bytes is a good starting value, assumes default info
    final ByteArrayOutputStream output = new ByteArrayOutputStream(256);
    try {
      final JsonGenerator json = JSON.getFactory().createGenerator(output); 
      json.writeStartObject();
      json.writeStringField("type", type.toString());
      json.writeStringField("displayName", display_name);
      json.writeStringField("description", description);
      json.writeStringField("notes", notes);
      json.writeNumberField("created", created);
      if (custom == null) {
        json.writeNullField("custom");
      } else {
        json.writeObjectFieldStart("custom");
        for (Map.Entry<String, String> entry : custom.entrySet()) {
          json.writeStringField(entry.getKey(), entry.getValue());
        }
        json.writeEndObject();
      }
      
      json.writeEndObject(); 
      json.close();
      return output.toByteArray();
    } catch (IOException e) {
      throw new RuntimeException("Unable to serialize UIDMeta", e);
    }
  }
  
  // Getters and Setters --------------
  
  /** @return the uid as a hex encoded string */
  public String getUID() {
    return uid;
  }

  /** @return the type of UID represented */
  public UniqueIdType getType() {
    return type;
  }

  /** @return the name of the UID object */
  public String getName() {
    return name;
  }

  /** @return optional display name, use {@code name} if empty */
  public String getDisplayName() {
    return display_name;
  }

  /** @return optional description */
  public String getDescription() {
    return description;
  }

  /** @return optional notes */
  public String getNotes() {
    return notes;
  }

  /** @return when the UID was first assigned, may be 0 if unknown */
  public long getCreated() {
    return created;
  }

  /** @return optional map of custom values from the user */
  public Map<String, String> getCustom() {
    return custom;
  }

  /** @param display_name an optional descriptive name for the UID */
  public void setDisplayName(final String display_name) {
    if (!this.display_name.equals(display_name)) {
      changed.put("display_name", true);
      this.display_name = display_name;
    }
  }

  /** @param description an optional description of the UID */
  public void setDescription(final String description) {
    if (!this.description.equals(description)) {
      changed.put("description", true);
      this.description = description;
    }
  }

  /** @param notes optional notes */
  public void setNotes(final String notes) {
    if (!this.notes.equals(notes)) {
      changed.put("notes", true);
      this.notes = notes;
    }
  }

  /** @param custom the custom to set */
  public void setCustom(final Map<String, String> custom) {
    // equivalency of maps is a pain, users have to submit the whole map
    // anyway so we'll just mark it as changed every time we have a non-null
    // value
    if (this.custom != null || custom != null) {
      changed.put("custom", true);
      this.custom = new HashMap<String, String>(custom);
    }
  }

  /** @param created the created timestamp Unix epoch in seconds */
  public final void setCreated(final long created) {
    if (this.created != created) {
      changed.put("created", true);
      this.created = created;
    }
  }
}
