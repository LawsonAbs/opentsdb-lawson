// This file is part of OpenTSDB.
// Copyright (C) 2010-2014  The OpenTSDB Authors.
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
package net.opentsdb.tsd;

import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.google.common.base.Objects;
import com.stumbleupon.async.Deferred;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.jboss.netty.handler.codec.http.QueryStringDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.opentsdb.core.TSDB;
import net.opentsdb.stats.QueryStats;

/**
 * Abstract base class for HTTP queries.
 * 对于HTTP请求的抽象类
 * 
 * @since 2.2
 */
public abstract class AbstractHttpQuery {
  private static final Logger LOG = LoggerFactory.getLogger(AbstractHttpQuery.class);

  /** When the query was started (useful for timing). */
  private final long start_time = System.nanoTime();

  /** The request in this HTTP query.
   *  在这个HTTP查询中的请求
   * */
  private final HttpRequest request;

  /** The channel on which the request was received. */
  private final Channel chan;

  /** Shortcut to the request method
   * 请求方法的缩写
   * */
  private final HttpMethod method;

  /** Parsed query string (lazily built on first access).
   *  解析查询字符串（延迟加载直到第一次访问）
   *
   *  01.是一个Map
   * */
  private Map<String, List<String>> querystring;
  
  /** Deferred result of this query, to allow asynchronous processing.
   * (Optional.) */
  protected final Deferred<Object> deferred = new Deferred<Object>();
  
  /** The response object we'll fill with data
   *  我们将用数据填充的响应对象
   *
   *  1.DefaultHttpResponse -> create a new instance
   *  01.version :the HTTP version of this response
   *  02.status :the status of this response
   * */
  private final DefaultHttpResponse response =
    new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);

  /** The {@code TSDB} instance we belong to */
  protected final TSDB tsdb;
  
  /** Used for recording query statistics */
  protected QueryStats stats;
  
  /**
   * Set up required internal state.  For subclasses.
   * 建立所需的内部状态。（为了子类）
   * @param request the incoming HTTP request
   *                即将到来的HTTP 请求
   * @param chan the {@link Channel} the request was received on
   */
  protected AbstractHttpQuery(final TSDB tsdb, final HttpRequest request, final Channel chan) {
    this.tsdb = tsdb;
    this.request = request;
    this.chan = chan;
    this.method = request.getMethod();//获取（获取请求的方式——是post还是get）
  }
  
  /**
   * Returns the underlying Netty {@link HttpRequest} of this query.
   * 返回这个query的底层Netty HttpRequest
   */
  public HttpRequest request() {
    return request;
  }

  /** Returns the HTTP method/verb for the request
   *  返回这个请求的HTTP方法/动作
   * */
  public HttpMethod method() {
    return this.method;
  }

  /** Returns the response object, allowing serializers to set headers */
  public DefaultHttpResponse response() {
    return this.response;
  }

  /**
   * Returns the underlying Netty {@link Channel} of this query.
   * 返回这个查询的底层Netty Channel
   */
  public Channel channel() {
    return chan;
  }

  /** @return The remote address and port in the format <ip>:<port> */
  public String getRemoteAddress() {
    return chan.getRemoteAddress().toString();
  }
  
  /**
   * Copies the header list and obfuscates the "cookie" header in case it
   * contains auth tokens, etc. Note that it flattens duplicate headers keys
   * as comma separated lists per the RFC
   * @return The full set of headers for this query with the cookie obfuscated
   */
  public Map<String, String> getPrintableHeaders() {
    final Map<String, String> headers = new HashMap<String, String>(
        request.getHeaders().size());
    for (final Entry<String, String> header : request.getHeaders()) {
      if (header.getKey().toLowerCase().equals("cookie")) {
        // null out the cookies
        headers.put(header.getKey(), "*******");
      } else {
        // http://tools.ietf.org/html/rfc2616#section-4.2
        if (headers.containsKey(header.getKey())) {
          headers.put(header.getKey(), 
              headers.get(header.getKey()) + "," + header.getValue());
        } else {
          headers.put(header.getKey(), header.getValue());
        }
      }
    }
    return headers;
  }
  
  /**
   * Copies the header list so modifications won't affect the original set. 
   * Note that it flattens duplicate headers keys as comma separated lists 
   * per the RFC
   * @return The full set of headers for this query
   */
  public Map<String, String> getHeaders() {
    final Map<String, String> headers = new HashMap<String, String>(
        request.getHeaders().size());
    for (final Entry<String, String> header : request.getHeaders()) {
      // http://tools.ietf.org/html/rfc2616#section-4.2
      if (headers.containsKey(header.getKey())) {
        headers.put(header.getKey(), 
            headers.get(header.getKey()) + "," + header.getValue());
      } else {
        headers.put(header.getKey(), header.getValue());
      }
    }
    return headers;
  }
  
  /** @param stats The stats object to mark after writing is complete */
  public void setStats(final QueryStats stats) {
    this.stats = stats;
  }
  
  /** Return the time in nanoseconds that this query object was 
   * created.
   */
  public long startTimeNanos() {
    return start_time;
  }

  /** Returns how many ms have elapsed since this query was created.
   * 返回自从query创建之后时间过去了多久
   * */
  public int processingTimeMillis() {
    return (int) ((System.nanoTime() - start_time) / 1000000);
  }
  
  /**
   * Returns the query string parameters passed in the URI.
   * 返回传递到URI中的查询字符串参数
   *
   * 01.注意返回的参数对象是：Map类实例
   * 02.
   */
  public Map<String, List<String>> getQueryString() {
    if (querystring == null) {
      try {
        //Creates a new decoder that decodes the specified URI. The decoder will assume that the query string is encoded in UTF-8.
        //创建一个新的decoder去解码指定的URI。这个解码器将会默认这个query字符串使用utf-8编码

        //getUri() :Returns the URI (or path) of this request. 返回这个请求对象的URI(或者path)
        //getParameters():Returns the decoded key-value parameter pairs of the URI.返回从URI中解码出的key-value参数对
        //new 一个QueryStringDecoder，用于解析query中的参数
        querystring = new QueryStringDecoder(request.getUri())
                .getParameters();
      } catch (IllegalArgumentException e) {
        throw new BadRequestException("Bad query string: " + e.getMessage());
      }
    }
    return querystring;
  }
  
  /**
   * Returns the value of the given query string parameter.
   * 返回给定查询字符串参数中的值
   *
   * <p>
   * If this parameter occurs multiple times in the URL, only the last value
   * is returned and others are silently ignored.
   * 如果这个参数在URL中出现多次，那么只有最后一个值会被返回，并且其它的值将会被忽略
   *
   * @param paramname Name of the query string parameter to get.
   * @return The value of the parameter or {@code null} if this parameter
   * wasn't passed in the URI.
   */
  public String getQueryStringParam(final String paramname) {
      //getQueryString()将会返回一个Map<String, List<String>> 类型对象 querystring
      //然后通过这个querystring 的get()方法获得paramname对应的值
    final List<String> params = getQueryString().get(paramname);
    return params == null ? null : params.get(params.size() - 1);
  }

  /**
   * Returns the non-empty value of the given required query string parameter.
   * <p>
   * If this parameter occurs multiple times in the URL, only the last value
   * is returned and others are silently ignored.
   * @param paramname Name of the query string parameter to get.
   * @return The value of the parameter.
   * @throws BadRequestException if this query string parameter wasn't passed
   * or if its last occurrence had an empty value ({@code &amp;a=}).
   */
  public String getRequiredQueryStringParam(final String paramname)
    throws BadRequestException {
    final String value = getQueryStringParam(paramname);
    if (value == null || value.isEmpty()) {
      throw BadRequestException.missingParameter(paramname);
    }
    return value;
  }

  /**
   * Returns whether or not the given query string parameter was passed.
   * 返回 给定的查询字符串参数 是否被解析了【是->true，否->false】
   *
   * @param paramname Name of the query string parameter to get.
   *                  通过这个查询字符串参数去获取
   *
   * @return {@code true} if the parameter
   */
  public boolean hasQueryStringParam(final String paramname) {
    return getQueryString().get(paramname) != null;
  }

  /**
   * Returns all the values of the given query string parameter.
   * 返回给定查询字符参数的所有值
   *
   * <p>
   * In case this parameter occurs multiple times in the URL, this method is
   * useful to get all the values.
   * 如果这个参数在URL中多次出现，这个方法对于获取所有值将是非常有用的
   *
   * @param paramname Name of the query string parameter to get.
   *                  需要获取的查询参数
   * @return The values of the parameter or {@code null} if this parameter
   * wasn't passed in the URI.
   *        参数值，如果这个参数没有被传入到URI中，那么将返回null.
   */
  public List<String> getQueryStringParams(final String paramname) {
    return getQueryString().get(paramname);
  }
  
  /**
   * Returns only the path component of the URI as a string
   * This call strips the protocol, host, port and query string parameters
   * leaving only the path e.g. "/path/starts/here"
   * <p>
   * Note that for slightly quicker performance you can call request().getUri()
   * to get the full path as a string but you'll have to strip query string
   * parameters manually.
   * @return The path component of the URI
   * @throws NullPointerException if the URI is null
   */
  public String getQueryPath() {
    return new QueryStringDecoder(request.getUri()).getPath();
  }
  
  /**
   * Returns the path component of the URI as an array of strings, split on the
   * forward slash
   * Similar to the {@link #getQueryPath} call, this returns only the path
   * without the protocol, host, port or query string params. E.g.
   * "/path/starts/here" will return an array of {"path", "starts", "here"}
   * <p>
   * Note that for maximum speed you may want to parse the query path manually.
   * @return An array with 1 or more components, note the first item may be
   * an empty string.
   * @throws BadRequestException if the URI is empty or does not start with a
   * slash
   * @throws NullPointerException if the URI is null
   */
  public String[] explodePath() {
    final String path = getQueryPath();
    if (path.isEmpty()) {
      throw new BadRequestException("Query path is empty");
    }
    if (path.charAt(0) != '/') {
      throw new BadRequestException("Query path doesn't start with a slash");
    }
    // split may be a tad slower than other methods, but since the URIs are
    // usually pretty short and not every request will make this call, we
    // probably don't need any premature optimization
    return path.substring(1).split("/");
  }
  
  /**
   * Parses the query string to determine the base route for handing a query
   * off to an RPC handler.
    解析查询字符串以确定将查询传递给RPC处理程序的基本路由。

   * @return the base route
   *        基础路由
   * @throws BadRequestException if some necessary part of the query cannot
   * be parsed.
   */
  public abstract String getQueryBaseRoute();
  
  /**
   * Attempts to parse the character set from the request header. If not set
   * defaults to UTF-8
   * @return A Charset object
   * @throws UnsupportedCharsetException if the parsed character set is invalid
   */
  public Charset getCharset() {
    // RFC2616 3.7
    for (String type : this.request.headers().getAll("Content-Type")) {
      int idx = type.toUpperCase().indexOf("CHARSET=");
      if (idx > 1) {
        String charset = type.substring(idx+8);
        return Charset.forName(charset);
      }
    }
    return Charset.forName("UTF-8");
  }
  
  /** @return True if the request has content, false if not.
   *            如果请求包涵内容，则返回true，否则返回false
   * */
  public boolean hasContent() {
    return this.request.getContent() != null &&
      this.request.getContent().readable();
  }

  /**
   * Decodes the request content to a string using the appropriate character set
   * 使用合适的字符集，将请求内容解析成一个字符串
   * @return Decoded content or an empty string if the request did not include
   * content
   * @throws UnsupportedCharsetException if the parsed character set is invalid
   */
  public String getContent() {
    return this.request.getContent().toString(this.getCharset());
  }
  
  /**
   * Method to call after writing the HTTP response to the wire.  The default 
   * is to simply log the request info.  Can be overridden by subclasses.
   */
  public void done() {
    final int processing_time = processingTimeMillis();
   final String url = request.getUri();
   final String msg = String.format("HTTP %s done in %d ms", url, processing_time);
   if (url.startsWith("/api/put") && LOG.isDebugEnabled()) {
     // NOTE: Suppresses too many log lines from /api/put.
     LOG.debug(msg);
   } else {
     logInfo(msg);
   }
    logInfo("HTTP " + request.getUri() + " done in " + processing_time + "ms");
  }
  
  /**
   * Sends <code>500/Internal Server Error</code> to the client.
   * @param cause The unexpected exception that caused this error.
   */
  public void internalError(final Exception cause) {
    logError("Internal Server Error on " + request().getUri(), cause);
    sendStatusOnly(HttpResponseStatus.INTERNAL_SERVER_ERROR);
  }

  /**
   * Sends <code>400/Bad Request</code> status to the client.
   * @param exception The exception that was thrown
   */
  public void badRequest(final BadRequestException exception) {
    logWarn("Bad Request on " + request().getUri() + ": " + exception.getMessage());
    sendStatusOnly(HttpResponseStatus.BAD_REQUEST);
  }

  /**
   * Sends <code>404/Not Found</code> to the client.
   */
  public void notFound() {
    logWarn("Not Found: " + request().getUri());
    sendStatusOnly(HttpResponseStatus.NOT_FOUND);
  }
  
  /**
   * Send just the status code without a body, used for 204 or 304
   * @param status The response code to reply with
   */
  public void sendStatusOnly(final HttpResponseStatus status) {
    if (!chan.isConnected()) {
      done();
      return;
    }

    response.setStatus(status);
    final boolean keepalive = HttpHeaders.isKeepAlive(request);
    if (keepalive) {
      HttpHeaders.setContentLength(response, 0);
    }
    final ChannelFuture future = chan.write(response);
    if (stats != null) {
      future.addListener(new SendSuccess());
    }
    if (!keepalive) {
      future.addListener(ChannelFutureListener.CLOSE);
    }
    done();
  }

  /**
   * Sends an HTTP reply to the client.
   * 发送一个HTTP应答给客户端
   * @param status The status of the request (e.g. 200 OK or 404 Not Found).
   * @param buf The content of the reply to send.
   */
  public void sendBuffer(final HttpResponseStatus status,
                          final ChannelBuffer buf,
                          final String contentType) {
    if (!chan.isConnected()) {
      done();
      return;
    }

    //为这个响应添加content_type
    response.headers().set(HttpHeaders.Names.CONTENT_TYPE, contentType);

    // TODO(tsuna): Server, X-Backend, etc. headers.
    // only reset the status if we have the default status, otherwise the user
    // already set it
    response.setStatus(status);
    response.setContent(buf);
    final boolean keepalive = HttpHeaders.isKeepAlive(request);
    if (keepalive) {
      HttpHeaders.setContentLength(response, buf.readableBytes());
    }
    final ChannelFuture future = chan.write(response);
    if (stats != null) {
      future.addListener(new SendSuccess());
    }
    if (!keepalive) {
      future.addListener(ChannelFutureListener.CLOSE);
    }
    done();
  }
  
  /** A simple class that marks a query as complete when the stats are set */
  private class SendSuccess implements ChannelFutureListener {
    @Override
    public void operationComplete(final ChannelFuture future) throws Exception {
      stats.markSent();
    }
  }
  
  /** @return Information about the query */
  public String toString() {
    return Objects.toStringHelper(this)
        .add("start_time", start_time)
        .add("request", request)
        .add("chan", chan)
        .add("querystring", querystring)
        .toString();
  }
  
  // ---------------- //
  // Logging helpers. //
  // ---------------- //
  
  /**
   * Logger for the query instance.
   */
  protected Logger logger() {
    return LOG;
  }

  protected final String logChannel() {
    if (request.containsHeader("X-Forwarded-For")) {
        String inetAddress;
        String proxyChain = request.getHeader("X-Forwarded-For");
        int firstComma = proxyChain.indexOf(',');
        if (firstComma != -1) {
          inetAddress = proxyChain.substring(0, proxyChain.indexOf(','));
        } else {
          inetAddress = proxyChain;
        }
        return "[id: 0x" + Integer.toHexString(chan.hashCode()) + ", /" + inetAddress + " => " + chan.getLocalAddress() + ']';
    } else {
        return chan.toString();
    }
  }

  protected final void logInfo(final String msg) {
    if (logger().isInfoEnabled()) {
      logger().info(logChannel() + ' ' + msg);
    }
  }

  protected final void logWarn(final String msg) {
    if (logger().isWarnEnabled()) {
      logger().warn(logChannel() + ' ' + msg);
    }
  }

  protected final void logError(final String msg, final Exception e) {
    if (logger().isErrorEnabled()) {
      logger().error(logChannel() + ' ' + msg, e);
    }
  }

}
