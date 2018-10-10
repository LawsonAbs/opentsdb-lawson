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
package net.opentsdb.tools;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.opentsdb.core.Aggregator;
import net.opentsdb.core.Aggregators;
import net.opentsdb.core.Query;
import net.opentsdb.core.DataPoint;
import net.opentsdb.core.DataPoints;
import net.opentsdb.core.RateOptions;
import net.opentsdb.core.Tags;
import net.opentsdb.core.TSDB;
import net.opentsdb.graph.Plot;
import net.opentsdb.utils.Config;
import net.opentsdb.utils.DateTime;

final class CliQuery {

  private static final Logger LOG = LoggerFactory.getLogger(CliQuery.class);

  /** Prints usage and exits with the given retval.  */
  private static void usage(final ArgP argp, final String errmsg,
                            final int retval) {
    System.err.println(errmsg);
    System.err.println("Usage: query"
        + " [Gnuplot opts] START-DATE [END-DATE] <query> [queries...]\n"
        + "A query has the form:\n"
        + "  FUNC [rate] [counter,max,reset] [downsample N FUNC] SERIES [TAGS]\n"
        + "For example:\n"
        + " 2010/03/11-20:57 sum my.awsum.metric host=blah"
        + " sum some.other.metric host=blah state=foo\n"
        + "Dates must follow this format: YYYY/MM/DD-HH:MM[:SS] or Unix Epoch\n"
        + " or relative time such as 1y-ago, 2d-ago, etc.\n"
        + "Supported values for FUNC: " + Aggregators.set()
        + "\nGnuplot options are of the form: +option=value");
    if (argp != null) {
      System.err.print(argp.usage());
    }
    System.exit(retval);
  }


  public static void main(String[] args) throws Exception {
    ArgP argp = new ArgP();

    CliOptions.addCommon(argp);//addCommon(argp)是将一些比较常用的参数放到argp中
    CliOptions.addVerbose(argp);
    argp.addOption("--graph", "BASEPATH",
                   "Output data points to a set of files for gnuplot."
                   + "  The path of the output files will start with"
                   + " BASEPATH.");
    args = CliOptions.parse(argp, args);
    if (args == null) {
      usage(argp, "Invalid usage.", 1);
    } else if (args.length < 3) {
      usage(argp, "Not enough arguments.", 2);
    }

    // get a config object
    Config config = CliOptions.getConfig(argp);
    
    final TSDB tsdb = new TSDB(config);
    tsdb.checkNecessaryTablesExist().joinUninterruptibly();
    final String basepath = argp.get("--graph");
    argp = null;//但是我不知道为什么这里让argp 置为null？【难道是因为有很多个argp需要解析，是避免argp干扰】

    Plot plot = null;//声明一个plot引用
    try {
      plot = doQuery(tsdb, args, basepath != null);//doQuery是本类中下面的这个doQuery()方法
    } finally {
      try {
        //shutdown()这个方法有很多地方需要研究！！！！
        tsdb.shutdown().joinUninterruptibly();
      } catch (Exception e) {
        LOG.error("Unexpected exception", e);
        System.exit(1);
      }
    }

    if (plot != null) {
      try {
        final int npoints = plot.dumpToFiles(basepath);
        LOG.info("Wrote " + npoints + " for Gnuplot");
      } catch (IOException e) {
        LOG.error("Failed to write the Gnuplot file under " + basepath, e);
        System.exit(1);
      }
    }
  }

  private static Plot doQuery(final TSDB tsdb,
                              final String args[],
                              final boolean want_plot) {
    final ArrayList<String> plotparams = new ArrayList<String>();//画图需要的参数
    final ArrayList<Query> queries = new ArrayList<Query>();//Query 是一个接口
    final ArrayList<String> plotoptions = new ArrayList<String>();//画图的选项

      // 本类中的方法
      //这个可以对参数queries,plotparams, plotoptions等进行修改
    parseCommandLineQuery(args, tsdb, queries, plotparams, plotoptions);

    //如果没有指定查询 -> 打印用法并退出
    if (queries.isEmpty()) {
      usage(null, "Not enough arguments, need at least one query.", 2);
    }

    //接着开始画图
      //定义一个plot对象，如果want_plot为true，则获取queries的startTime和endTIme，否则将plot指为null
    final Plot plot = (want_plot ? new Plot(queries.get(0).getStartTime(),
                                            queries.get(0).getEndTime())
                       : null);

    //这个表示的是开始画图
    if (want_plot) {
      plot.setParams(parsePlotParams(plotparams));
    }

    //nqueries表示n queries，即查询的个数
    final int nqueries = queries.size();

    //并行化执行n个query
    for (int i = 0; i < nqueries; i++) {
      // TODO(tsuna): Optimization: run each query in parallel.
      final StringBuilder buf = want_plot ? null : new StringBuilder();

      //queries.get(i)得到的是一个Query 对象
      //所以，run()方法就是
      for (final DataPoints datapoints : queries.get(i).run()) {
        if (want_plot) {
          plot.add(datapoints, plotoptions.get(i));
        } else {
          final String metric = datapoints.metricName();
          final String tagz = datapoints.getTags().toString();
          for (final DataPoint datapoint : datapoints) {
            buf.append(metric)
               .append(' ')
               .append(datapoint.timestamp())
               .append(' ');
            if (datapoint.isInteger()) {
              buf.append(datapoint.longValue());
            } else {
              buf.append(String.format("%f", datapoint.doubleValue()));
            }
            buf.append(' ').append(tagz).append('\n');
            System.out.print(buf);
            buf.delete(0, buf.length());
          }
        }
      }
    }
    return plot;
  }

  /**
   * Parses the query from the command lines.
   * 解析来自命令行的查询
   *
   * @param args The command line arguments.
   * @param tsdb The TSDB to use.
   * @param queries The list in which {@link Query}s will be appended.
   * @param plotparams The list in which global plot parameters will be
   * appended.  Ignored if {@code null}.
   * @param plotoptions The list in which per-line plot options will be
   * appended.  Ignored if {@code null}.
   */

  //按照i的次数进行解析
  static void parseCommandLineQuery(final String[] args,
                                    final TSDB tsdb,
                                    final ArrayList<Query> queries,
                                    final ArrayList<String> plotparams,
                                    final ArrayList<String> plotoptions) {

    //获取start_time
    long start_ts = DateTime.parseDateTimeString(args[0], null);
    if (start_ts >= 0)
      start_ts /= 1000; //get second rather than milli second

      long end_ts = -1;
    if (args.length > 3){
      // see if we can detect an end time
        //验证是否能够获取一个end time
      try{
      if (args[1].charAt(0) != '+'
           && (args[1].indexOf(':') >= 0
               || args[1].indexOf('/') >= 0
               || args[1].indexOf('-') >= 0
               || Long.parseLong(args[1]) > 0)){
          end_ts = DateTime.parseDateTimeString(args[1], null);
        }
      }catch (NumberFormatException nfe) {
        // ignore it as it means the third parameter is likely the aggregator
      }
    }
    // temp fixup to seconds from ms until the rest of TSDB supports ms
    // Note you can't append this to the DateTime.parseDateTimeString() call as
    // it clobbers -1 results
    if (end_ts >= 0)
      end_ts /= 1000;

      //为什么这里使用的是end_ts与0比较？
      //因为end_ts的默认值是-1，如果没有修改该值，所以end_ts<0  => args参数中不存在end_time字段 将i设置成1
      // 反之end_ts >= 0，args参数中存在end_time字段 将i设置成2
    int i = end_ts < 0 ? 1 : 2;

    //这个部分添加的应该是聚合参数【我不确定】
    while (i < args.length && args[i].charAt(0) == '+') {
      if (plotparams != null) {
        plotparams.add(args[i]);
      }
      i++;
    }


    while (i < args.length) {
        //注意这里是Aggregators 而不是Aggregator。
        //在Aggregators 这个类中有一个实例：private static final HashMap<String, Aggregator> aggregators;
        //map的映射关系是String -> Aggregator
        //根据args[i++] = name 获取一个Aggregator 对象；Aggregators只不过是一个方法类而已【相当于ArrayUtils】
        final Aggregator agg = Aggregators.get(args[i++]);//聚合参数
      //执行完上面的语句，然后执行i++操作

      final boolean rate = args[i].equals("rate");
      RateOptions rate_options = new RateOptions(false, Long.MAX_VALUE,
          RateOptions.DEFAULT_RESET_VALUE);
      if (rate) {
        i++;
        
        long counterMax = Long.MAX_VALUE;
        long resetValue = RateOptions.DEFAULT_RESET_VALUE;
        if (args[i].startsWith("counter")) {
          String[] parts = Tags.splitString(args[i], ',');
          if (parts.length >= 2 && parts[1].length() > 0) {
            counterMax = Long.parseLong(parts[1]);
          }
          if (parts.length >= 3 && parts[2].length() > 0) {
            resetValue = Long.parseLong(parts[2]);
          }
          rate_options = new RateOptions(true, counterMax, resetValue);
          i++;
        }
      }

      //是否downsample
      final boolean downsample = args[i].equals("downsample");
      if (downsample) {
        i++;
      }

      //如果有downsample，那么就去args[i++]的值，否则取0【因为没有interval】
      final long interval = downsample ? Long.parseLong(args[i++]) : 0;
      final Aggregator sampler = downsample ? Aggregators.get(args[i++]) : null;

      final String metric = args[i++];//获取metric
      final HashMap<String, String> tags = new HashMap<String, String>();//获取tag对
      while (i < args.length && args[i].indexOf(' ', 1) < 0
             && args[i].indexOf('=', 1) > 0) {
        Tags.parse(tags, args[i++]);
      }
      if (i < args.length && args[i].indexOf(' ', 1) > 0) {
        plotoptions.add(args[i++]);
      }

      //构建了一个query对象，这个query对象中有一个当前tsdb对象的引用
      final Query query = tsdb.newQuery();
      query.setStartTime(start_ts);//设置查询起始时间
      if (end_ts > 0) {
        query.setEndTime(end_ts);//设置查询结束时间
      }
      query.setTimeSeries(metric, tags, agg, rate, rate_options);//设置时间序列的一系列值
      if (downsample) {
        query.downsample(interval, sampler);
      }
      queries.add(query);//难道有多个query被添加到queries中？【这个地方稍有不理解】
    }
  }



  //解析画图的参数
  private static HashMap<String, String> parsePlotParams(final ArrayList<String> params) {
    final HashMap<String, String> result =
      new HashMap<String, String>(params.size());//分配一个params这样的大小map

    for (final String param : params) {
      Tags.parse(result, param.substring(1));//借用Tags这个类将params处理
    }
    return result;
  }
}
