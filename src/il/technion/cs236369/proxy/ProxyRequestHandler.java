package il.technion.cs236369.proxy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.logging.Logger;

import javax.net.SocketFactory;

import org.apache.http.Header;
import org.apache.http.HeaderIterator;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpMessage;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.impl.DefaultHttpClientConnection;
import org.apache.http.impl.DefaultHttpServerConnection;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.BasicHttpProcessor;
import org.apache.http.protocol.ExecutionContext;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpRequestExecutor;
import org.apache.http.protocol.HttpRequestHandler;
import org.apache.http.util.EntityUtils;

public class ProxyRequestHandler implements HttpRequestHandler {

    private static Logger log = Logger.getLogger(HttpProxy.class.getName());
    private static final BasicHttpResponse ERR_RESPONSE = new BasicHttpResponse(HttpVersion.HTTP_1_1, 500, "Internal Server Error");

    private SocketFactory       mSockFact;
    private BasicHttpProcessor  mOutHttpProc;
    private HttpRequestExecutor mHttpExec;
    private HttpParams          mHttpParams;

    public ProxyRequestHandler(HttpParams httpParams,
                               BasicHttpProcessor outHttpProc,
                               HttpRequestExecutor httpExec,
                               SocketFactory sockFact) {
        mHttpParams = httpParams;
        mOutHttpProc = outHttpProc;
        mHttpExec = httpExec;
        mSockFact = sockFact;
    }

    private void logHeaders(HttpMessage msg) {
        if (msg == null)
            return;

        HeaderIterator hi = msg.headerIterator();
        StringBuilder headers = new StringBuilder();
        headers.append("\n");
        if (msg instanceof HttpRequest)
            headers.append("Request Headers:\n");
        else if (msg instanceof HttpResponse)
            headers.append("Response Headers:\n");
        else
            headers.append("Headers of unkown type of HttpMessage");
        while (hi.hasNext())
            headers.append("\t" + hi.next().toString() + "\n");
        log.info(headers.toString());
    }

    @Override
    public void handle(HttpRequest request, HttpResponse response, HttpContext context) {
        logHeaders(request);
        ProxyCache cache = (ProxyCache) context.getAttribute(HttpProxy.CACHE);
        DefaultHttpServerConnection clientConn = (DefaultHttpServerConnection) context.getAttribute(HttpProxy.CLIENT_CONN);


        HttpHost targetHost = new HttpHost(request.getFirstHeader("Host").getValue(), 80);

        // Set up outgoing HTTP connection
        Socket outsocket;
        try {
            outsocket = mSockFact.createSocket(targetHost.getHostName(), targetHost.getPort());
        }
        catch (UnknownHostException e) {
            System.err.println("[!] Unknown destination host");
            return;
        }
        catch (IOException e) {
            System.err.println("[!] Destination socket creation failed");
            return;
        }

        DefaultHttpClientConnection targetConn = new DefaultHttpClientConnection();
        try {
            try {
                targetConn.bind(outsocket, mHttpParams);
            }
            catch (IOException e) {
                System.err.println("[!] Failed to create connection to destination host");
                return;
            }

            log.info("Outgoing connection to " + outsocket.getInetAddress());

            context.setAttribute(HttpProxy.TARGET_CONN, targetConn);

            context.setAttribute(ExecutionContext.HTTP_CONNECTION, targetConn);
            context.setAttribute(ExecutionContext.HTTP_TARGET_HOST, targetHost);

            log.info("Request URI: " + request.getRequestLine().getUri());

            removeHopByHopHeaders(request);

            HttpResponse targetResponse;
            try {
                mHttpExec.preProcess(request, mOutHttpProc, context);
                targetResponse = mHttpExec.execute(request, targetConn, context);
                mHttpExec.postProcess(response, mOutHttpProc, context);
            }
            catch (Exception e) {
                System.err.println("[!] Failed to execute the request");
                try {
                    clientConn.sendResponseHeader(ERR_RESPONSE);
                    clientConn.flush();
                }
                catch (Exception e2) {
                    System.err.println("[!] Failed to send error respones to client");
                }
                return;
            }

            removeHopByHopHeaders(targetResponse);

            response.setStatusLine(targetResponse.getStatusLine());
            response.setHeaders(targetResponse.getAllHeaders());
            try {
                copyResponseBody(targetResponse, response);
            }
            catch (IOException e) {
                System.err.println("[!] Failed to copy response body");
            }

            logHeaders(response);
        }
        finally {
            try {
                targetConn.close();
            }
            catch (IOException ignore) {}
        }

        // ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        // Cache the response
        //

        if (shouldCache(request, response)) {
            cache.store(request.getRequestLine().getUri(),
                        response.getAllHeaders(),
                        getCopyOfBody(response));
        }
    }

    private boolean shouldCache(HttpRequest request, HttpResponse response) {
        Header cacheHeader = response.getFirstHeader("Cache-control");
        String requestMethod = request.getRequestLine().getMethod().toUpperCase(Locale.ENGLISH);

        return requestMethod.equals("GET")
               && response.getStatusLine().getStatusCode() == 200
               && (cacheHeader == null || cacheHeader.getValue().equals("no-cache") || cacheHeader.getValue().equals("no-store"))
               && response.getFirstHeader("Last-Modified") != null;
    }

    private byte[] getCopyOfBody(HttpResponse msg) {
        try {
            HttpEntity responseEntity = msg.getEntity();

            byte[] body = new byte[0];
            if (responseEntity == null)
                return body;

            body = EntityUtils.toByteArray(responseEntity);
            byte[] returnBody = body.clone();

            // now reset the body of the original msg
            BasicHttpEntity entity = new BasicHttpEntity();
            InputStream stream = new ByteArrayInputStream(body);
            entity.setContentLength(stream.available());
            entity.setContent(stream);
            entity.setContentType(msg.getFirstHeader("Content-Type"));
            msg.setEntity(entity);

            return returnBody;
        }
        catch (Exception e) {
            return new byte[0];
        }
    }

    private void copyResponseBody(HttpResponse from, HttpResponse to) throws IOException {
        HttpEntity responseEntity = from.getEntity();

        byte[] body = new byte[0];
        if (responseEntity != null)
            body = EntityUtils.toByteArray(responseEntity);

        BasicHttpEntity entity = new BasicHttpEntity();
        InputStream stream = new ByteArrayInputStream(body);
        entity.setContentLength(stream.available());
        entity.setContent(stream);
        entity.setContentType(from.getFirstHeader("Content-Type"));
        to.setEntity(entity);
    }

    private void removeHopByHopHeaders(HttpMessage msg) {
        msg.removeHeaders(HTTP.CONTENT_LEN);
        msg.removeHeaders(HTTP.TRANSFER_ENCODING);
        msg.removeHeaders(HTTP.CONN_DIRECTIVE);
        msg.removeHeaders("Keep-Alive");
        msg.removeHeaders("TE");
        msg.removeHeaders("Trailers");
        msg.removeHeaders("Upgrade");
        msg.removeHeaders("Proxy-Authenticate");
    }
}
