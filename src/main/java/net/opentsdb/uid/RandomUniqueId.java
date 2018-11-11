// This file is part of OpenTSDB.
// Copyright (C) 2015  The OpenTSDB Authors.
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

import java.security.SecureRandom;

import net.opentsdb.utils.CustomedMethod;
import org.hbase.async.Bytes;
import net.opentsdb.core.TSDB;

/**
 * Generate Random UIDs to be used as unique ID.
 * 生成一个随机的UIDs用于unique ID。
 *
 * Random metric IDs help to distribute hotspots evenly to region servers.
 * 随机的metric IDs有助于分布 hotspots 均匀地到region servers.
 *
 * It is better to decide whether to use random or serial uid for one type when 
 * the hbase uid table is empty. If the logic to switch between random or serial
 * uid is changed in between writes it will cause frequent id collisions.
 * 当hbase uid 表是空的时候，很容易决定是否为一种类型使用随机或者序列化的uid。如果在写的时候，切换了随机或者序列uid的逻辑，这将会造成频繁的碰撞。
 * @since 2.2
 */
public class RandomUniqueId {
  /** Use the SecureRandom class to avoid blocking calls
   *  使用SecureRandom 类避免阻塞调用
   * */
  private static SecureRandom random_generator = new SecureRandom(
      Bytes.fromLong(System.currentTimeMillis()));
  
  /** Used to limit UIDs to unsigned longs */
  public static final int MAX_WIDTH = 7;
  
  /**
   * Get the next random metric UID, a positive integer greater than zero.
   * 获取下一个随机metric UID，一个正整数
   *
   * The default metric ID width is 3 bytes. If it is 3 then it can return
   * only up to the max value a 3 byte integer can return, which is 2^31-1. 
   * In that case, even though it is long, its range will be between 0 
   * and 2^31-1.
   * 默认的metric ID是3字节。如果它是3，那么它能仅仅返回到最大值 一个3字节整数 能够返回，它是2^31 -1.
   * 在这种个情况下，即使它是long，它的范围将会在0 —— 2^31 -1
   *
   * NOTE: The caller is responsible for assuring that the UID hasn't been
   * assigned yet.
   * 调用者有责任去保证UID未被分配
   *
   * @return a random UID up to {@link TSDB.metrics_width} wide
   *        一个随机的UID，TSDB.metric_width 宽
   */
  public static long getRandomUID() {
    return getRandomUID(TSDB.metrics_width());
  }

  /**
   * Get the next random UID. It creates random bytes, then convert it to an
   * unsigned long.
   * 获取下一个随机的UID。它创建随机的字节，然后转换成一个无符号的long型
   * @param width Number of bytes to randomize, it can not be larger 
   * than {@link MAX_WIDTH} bytes wide
   *              随机化的字节数，它不能比MAX_WIDTH 字节更宽
   *
   * @return a randomly UID
   *           一个随机化的UID
   * @throws throws IllegalArgumentException if the width is larger than 
   * {@link MAX_WIDTH} bytes
   */
  public static long getRandomUID(final int width) {

      //width不符合逻辑
      if (width > MAX_WIDTH) {
      throw new IllegalArgumentException("Expecting to return an unsigned long "
          + "random integer, it can not be larger than " + MAX_WIDTH + 
          " bytes wide");
    }

    //new 一个width的byte[]
    final byte[] bytes = new byte[width];

      //nextBytes: Generates a user-specified number of random bytes.
      //产生一个用户指定数目的随机字节
      random_generator.nextBytes(bytes);

    long value = 0;
      CustomedMethod.printSuffix(bytes.length+"");
    for (int i = 0; i<bytes.length; i++){
      value <<= 8; //将value的值左移8位 再赋值给value
      value |= bytes[i] & 0xFF;
    }

    // make sure we never return 0 as a UID
      //确保不会返回0作为UID
    return value != 0 ? value : value + 1;
  }
}