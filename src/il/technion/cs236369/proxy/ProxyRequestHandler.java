package il.technion.cs236369.proxy;

import java.io.IOException;
import java.net.Socket;
import java.util.logging.Logger;

import javax.net.SocketFactory;

import org.apache.http.HeaderIterator;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.impl.DefaultHttpClientConnection;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.BasicHttpProcessor;
import org.apache.http.protocol.ExecutionContext;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpRequestExecutor;
import org.apache.http.protocol.HttpRequestHandler;

public class ProxyRequestHandler implements HttpRequestHandler {

    private static Logger log = Logger.getLogger(ProxyRequestHandler.class.getName());
    private static final String HTTP_OUT_CONN = "http.proxy.out-conn";

    private SocketFactory mSockFact;
    private BasicHttpProcessor mOutHttpProc;
    private HttpRequestExecutor mHttpExec;
    private HttpParams mHttpParams;

    public ProxyRequestHandler(HttpParams httpParams,
                               BasicHttpProcessor outHttpProc,
                               HttpRequestExecutor httpExec,
                               SocketFactory sockFact) {
        mHttpParams = httpParams;
        mOutHttpProc = outHttpProc;
        mHttpExec = httpExec;
        mSockFact = sockFact;
    }

    @Override
    public void handle(HttpRequest request,
                       HttpResponse response,
                       HttpContext context) throws HttpException, IOException {

        // log request headers
        HeaderIterator hi = request.headerIterator();
        StringBuilder headers = new StringBuilder();
        headers.append("\n");
        while (hi.hasNext())
            headers.append("\t" + hi.next().toString() + "\n");
        log.info("\t[Request headers] : " + headers.toString());
        // ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

        HttpHost targetHost = new HttpHost(request.getFirstHeader("Host").getValue(), 80);

        // Set up outgoing HTTP connection
        Socket outsocket = mSockFact.createSocket(targetHost.getHostName(), targetHost.getPort());
        DefaultHttpClientConnection outconn = new DefaultHttpClientConnection();
        outconn.bind(outsocket, mHttpParams);
        log.info("Outgoing connection to " + outsocket.getInetAddress());

        context.setAttribute(HTTP_OUT_CONN, outconn);

        context.setAttribute(ExecutionContext.HTTP_CONNECTION, outconn);
        context.setAttribute(ExecutionContext.HTTP_TARGET_HOST, targetHost);

        log.info("[Request URI] : " + request.getRequestLine().getUri() + "\n");

        // Remove hop-by-hop headers
        request.removeHeaders(HTTP.CONTENT_LEN);
        request.removeHeaders(HTTP.TRANSFER_ENCODING);
        request.removeHeaders(HTTP.CONN_DIRECTIVE);
        request.removeHeaders("Keep-Alive");
        request.removeHeaders("Proxy-Authenticate");
        request.removeHeaders("TE");
        request.removeHeaders("Trailers");
        request.removeHeaders("Upgrade");

        mHttpExec.preProcess(request, mOutHttpProc, context);
        HttpResponse targetResponse = mHttpExec.execute(request, outconn, context);
        mHttpExec.postProcess(response, mOutHttpProc, context);

        // Remove hop-by-hop headers
        targetResponse.removeHeaders(HTTP.CONTENT_LEN);
        targetResponse.removeHeaders(HTTP.TRANSFER_ENCODING);
        targetResponse.removeHeaders(HTTP.CONN_DIRECTIVE);
        targetResponse.removeHeaders("Keep-Alive");
        targetResponse.removeHeaders("TE");
        targetResponse.removeHeaders("Trailers");
        targetResponse.removeHeaders("Upgrade");

        response.setStatusLine(targetResponse.getStatusLine());
        response.setHeaders(targetResponse.getAllHeaders());
        response.setEntity(targetResponse.getEntity());

        log.info("[Response] : " + response.getStatusLine() + "\n");

        // log response headers
        hi = response.headerIterator();
        headers = new StringBuilder();
        headers.append("\n");
        while (hi.hasNext())
            headers.append("\t" + hi.next().toString() + "\n");
        log.info("\t[Response headers] : " + headers.toString());
        // ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
    }
}
