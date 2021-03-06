package io.viper.core.server.file.s3;


import com.amazon.s3.QueryStringAuthGenerator;
import com.amazon.s3.Utils;
import io.viper.core.server.Util;
import io.viper.core.server.router.Route;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.*;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.codec.frame.TooLongFrameException;
import org.jboss.netty.handler.codec.http.*;
import org.jboss.netty.util.CharsetUtil;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.jboss.netty.handler.codec.http.HttpHeaders.Names;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static org.jboss.netty.handler.codec.http.HttpHeaders.setContentLength;
import static org.jboss.netty.handler.codec.http.HttpMethod.GET;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.*;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;


// TODO: add caching layer
public class S3StaticFileServerHandler extends Route
{
  private final QueryStringAuthGenerator _s3AuthGenerator;
  private final String _bucketName;

  private final ClientSocketChannelFactory _cf;
  private final String _amazonHost;
  private final int _amazonPort;
  private Channel _s3Channel;

  private HttpRequest _request;
  private Channel _destChannel;
  private String _bucketKey;

  private enum State
  {
    init,
    connected,
    relay,
    closed
  }

  private State _state = State.closed;

  private static File _indexFile = null;

  // TODO: add support for index.htm
  public S3StaticFileServerHandler(String route, String awsId, String awsKey, String bucketName)
    throws URISyntaxException
  {
    this(
      route,
      new QueryStringAuthGenerator(awsId, awsKey),
      bucketName,
      new NioClientSocketChannelFactory(Executors.newCachedThreadPool(), Executors.newCachedThreadPool()),
      Utils.DEFAULT_HOST,
      Utils.INSECURE_PORT);
  }

  public S3StaticFileServerHandler(
    String route,
    QueryStringAuthGenerator s3AuthGenerator,
    String bucketName,
    ClientSocketChannelFactory cf,
    String amazonHost,
    int amazonPort)
  {
    super(route);

    _s3AuthGenerator = s3AuthGenerator;
    _bucketName = bucketName;
    _cf = cf;
    _amazonHost = amazonHost;
    _amazonPort = amazonPort;
  }

  private void connect(final ChannelHandlerContext ctx)
  {
    ClientBootstrap cb = new ClientBootstrap(_cf);
    cb.getPipeline().addLast("encoder", new HttpRequestEncoder());
    cb.getPipeline().addLast("decoder", new HttpResponseDecoder());
    cb.getPipeline().addLast("handler", new S3ResponseHandler(_destChannel));
    ChannelFuture f = cb.connect(new InetSocketAddress(_amazonHost, _amazonPort));

    _s3Channel = f.getChannel();
    f.addListener(new ChannelFutureListener()
    {
      @Override
      public void operationComplete(ChannelFuture future)
        throws Exception
      {
        if (future.isSuccess())
        {
          _state = State.connected;

          Map<String, List<String>> headers = new HashMap<String, List<String>>();
          String uri = _s3AuthGenerator.get(_bucketName, _bucketKey, headers);

          HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);

          ChannelFuture q = _s3Channel.write(request);
          q.addListener(new ChannelFutureListener()
          {
            @Override
            public void operationComplete(ChannelFuture future)
              throws Exception
            {
              if (future.isSuccess())
              {
                _state = State.relay;
              }
              else
              {
                sendError(ctx.getChannel(), INTERNAL_SERVER_ERROR);
                closeS3Channel();
              }
            }
          });
        }
        else
        {
          closeS3Channel();
        }
      }
    });
  }

  @Override
  public void messageReceived(ChannelHandlerContext ctx, MessageEvent e)
    throws Exception
  {
    Object obj = e.getMessage();
    if (!(obj instanceof HttpRequest))
    {
      ctx.sendUpstream(e);
      return;
    }

    _request = (HttpRequest) e.getMessage();

    String uri = _request.getUri();
    final String path = Util.sanitizeUri(uri);
    if (path == null)
    {
      sendError(ctx.getChannel(), FORBIDDEN);
      return;
    }

    if (_request.getMethod() != GET)
    {
      sendError(ctx.getChannel(), METHOD_NOT_ALLOWED);
      return;
    }

    _destChannel = e.getChannel();
    _bucketKey = uri.substring(uri.lastIndexOf("/")+1);

    _state = State.init;

    connect(ctx);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e)
    throws Exception
  {
    Channel ch = e.getChannel();
    Throwable cause = e.getCause();
    if (cause instanceof TooLongFrameException)
    {
      sendError(ctx.getChannel(), BAD_REQUEST);
      return;
    }

    cause.printStackTrace();
    if (ch.isConnected())
    {
      sendError(ctx.getChannel(), INTERNAL_SERVER_ERROR);
    }

    if (_s3Channel != null)
    {
      _s3Channel.close();
      _s3Channel = null;
    }
  }

  private static void sendError(Channel channel, HttpResponseStatus status)
  {
    HttpResponse response = new DefaultHttpResponse(HTTP_1_1, status);
    response.setHeader(CONTENT_TYPE, "text/plain; charset=UTF-8");
    response.setContent(ChannelBuffers.copiedBuffer("Failure: " + status.toString() + "\r\n", CharsetUtil.UTF_8));

    // Close the connection as soon as the error message is sent.
    if (channel.isWritable())
    {
      channel.write(response).addListener(ChannelFutureListener.CLOSE);
    }
  }

  private class S3ResponseHandler extends SimpleChannelUpstreamHandler
  {
    private Channel _destChannel;

    S3ResponseHandler(Channel destChannel)
    {
      _destChannel = destChannel;
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, final MessageEvent e)
      throws Exception
    {
      if (!_state.equals(State.relay))
      {
        return;
      }

      Object obj = e.getMessage();
      if (obj instanceof HttpResponse)
      {
        if (!_destChannel.isWritable())
        {
          closeS3Channel();
          return;
        }

        HttpResponse m = (HttpResponse)obj;

        if (m.getStatus().equals(HttpResponseStatus.OK))
        {
          String contentType = m.getHeader(Names.CONTENT_TYPE);
          long contentLength = Long.parseLong(m.getHeader(Names.CONTENT_LENGTH));

          DefaultHttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
          response.setHeader(HttpHeaders.Names.CONTENT_TYPE, contentType);
          setContentLength(response, contentLength);
          response.setContent(m.getContent());
          ChannelFuture f = _destChannel.write(response);
          f.addListener(new ChannelFutureListener()
          {
            @Override
            public void operationComplete(ChannelFuture future)
                throws Exception
            {
              if (!future.isSuccess())
              {
                closeS3Channel();
              }
            }
          });
        }
        else
        {
          HttpResponseStatus status =
            (m.getStatus().equals(HttpResponseStatus.NOT_FOUND))
              ? m.getStatus()
              : INTERNAL_SERVER_ERROR;
          sendError(_destChannel, status);
          closeS3Channel();
        }
      }
      else if (obj instanceof HttpChunk)
      {
        if (!_destChannel.isWritable())
        {
          closeS3Channel();
          return;
        }

        HttpChunk chunk = (HttpChunk)obj;
        ChannelFuture f = _destChannel.write(chunk);
        f.addListener(new ChannelFutureListener()
        {
          @Override
          public void operationComplete(ChannelFuture future)
              throws Exception
          {
            if (!future.isSuccess())
            {
              closeS3Channel();
            }
          }
        });

        if (!_destChannel.isWritable())
        {
          if (_s3Channel != null)
          {
            _s3Channel.setReadable(false);
          }
        }

        if (chunk.isLast())
        {
          closeS3Channel();
        }
      }
    }

    @Override
    public void channelInterestChanged(ChannelHandlerContext ctx, ChannelStateEvent e)
      throws Exception
    {
      // If _s3Channel is not saturated anymore, continue accepting
      // the incoming traffic from the inboundChannel.
      if (e.getChannel().isWritable())
      {
        if (_s3Channel != null)
        {
          _s3Channel.setReadable(true);
        }
      }
    }

    @Override
    public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e)
      throws Exception
    {
      closeOnFlush(_destChannel);
      closeS3Channel();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e)
      throws Exception
    {
      e.getCause().printStackTrace();
      closeOnFlush(_destChannel);
      closeS3Channel();
    }
  }

  private void closeS3Channel()
  {
    // TODO: support keepalive

    _state = State.closed;
    if (_s3Channel != null)
    {
      _s3Channel.close();
      _s3Channel = null;
    }
  }

  /**
   * Closes the specified channel after all queued write requests are flushed.
   */
  static void closeOnFlush(Channel ch)
  {
    if (ch.isConnected())
    {
      if (ch.isWritable())
      {
        ch.write(ChannelBuffers.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
      }
    }
  }
}
