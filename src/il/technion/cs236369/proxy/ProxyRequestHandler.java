package il.technion.cs236369.proxy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.logging.Logger;

import javax.net.SocketFactory;

import org.apache.http.Header;
import org.apache.http.HeaderIterator;
import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpHost;
import org.apache.http.HttpMessage;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.HttpVersion;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.impl.DefaultHttpClientConnection;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.BasicHttpProcessor;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpRequestExecutor;
import org.apache.http.protocol.HttpRequestHandler;
import org.apache.http.util.EntityUtils;

public class ProxyRequestHandler implements HttpRequestHandler
{
    private static Logger       log          = Logger
                                                 .getLogger(HttpProxy.class.getName());

    private static int          DEFAULT_PORT = 80;

    private SocketFactory       m_sockFact;
    private BasicHttpProcessor  m_outhttpproc;
    private HttpRequestExecutor m_httpexec;
    private HttpParams          m_httpparams;
    private ProxyCache          m_cache;

    public ProxyRequestHandler(HttpParams httpparams, BasicHttpProcessor outhttpproc,
                               HttpRequestExecutor httpexec, SocketFactory sockfact,
                               ProxyCache cache) {
        m_httpparams = httpparams;
        m_outhttpproc = outhttpproc;
        m_httpexec = httpexec;
        m_sockFact = sockfact;
        m_cache = cache;
    }

    @Override
    public void handle(HttpRequest request, HttpResponse response, HttpContext context)
        throws UnknownHostException, HttpException, IOException
    {
        logHeaders(request);

        String requestUri = request.getRequestLine().getUri();
        log.info("Handling request for: " + requestUri);

        byte[] resbody = new byte[0];

        if (canUseCache(request)) {
            String requestMethod = request.getRequestLine().getMethod()
                .toUpperCase(Locale.ENGLISH);

            log.info("allowed to use cache");
            log.info("request method is " + requestMethod);

            if (requestMethod.equals("GET") && m_cache.contains(requestUri)) {

                log.info("cache contains the request url");

                ProxyCache.CachedItem cachedEntry = m_cache.retrieve(requestUri);
                HttpResponse validationRes = validateCacheEntry(cachedEntry);
                int rescode = validationRes.getStatusLine().getStatusCode();

                log.info("validation response code = " + rescode);

                if (rescode == HttpStatus.SC_NOT_MODIFIED) {
                    setCachedResponse(cachedEntry, response);
                    return;
                }

                if (rescode != HttpStatus.SC_OK) {
                    m_cache.invalidate(requestUri);
                }
                else {
                    log.info("setting body from the validation response and updating cache if possible...");

                    resbody = copyResponse(validationRes, response);

                    logHeaders(response);

                    if (isCacheable(request, response))
                        m_cache.store(requestUri, response.getAllHeaders(), resbody);

                    return;
                }
            }
        }
        else
            log.info("request is NOT cacheable");


        resbody = performRequest(request, response, context);

        if (isCacheable(request, response))
            m_cache.store(requestUri, response.getAllHeaders(), resbody);
    }

    // ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

    /**
     * Given a request determine if we can possibly use the proxy cache to
     * respond.
     * @param request
     * @return true if it is posisble to use the cache, false otherwise
     */
    private boolean canUseCache(HttpRequest request)
    {
        Header[] cacheHeaders = request.getHeaders("Cache-Control");
        for (Header h : cacheHeaders) {
            String hval = h.getValue();
            if (hval.equals("no-store") || hval.equals("no-cache"))
                return false;
        }

        return true;
    }

    /**
     * sets the response to contain data retrieved from the cache
     * @param entry
     * @param response
     * @throws IOException
     */
    private void setCachedResponse(ProxyCache.CachedItem entry, HttpResponse response) throws IOException
    {
        log.info("setting response from cache");

        for (Header h : entry.m_headers)
            response.addHeader(h);
        setResponseEntity(response, entry.m_body, response.getFirstHeader("Content-Type"));
    }

    /**
     * Performs the actual request
     * @param request
     * @param response
     * @param context
     * @return the body of the response
     * @throws UnknownHostException
     * @throws IOException
     * @throws HttpException
     */
    private byte[] performRequest(HttpRequest request, HttpResponse response, HttpContext context)
        throws UnknownHostException, IOException, HttpException
    {

        String requestUri = request.getRequestLine().getUri();
        // String requestHost = request.getFirstHeader("Host").getValue();
        String requestHost = getHost(requestUri);
        int requestPort = getPort(requestUri);

        log.info("target host: " + requestHost);
        log.info("target port: " + requestPort);

        HttpHost requestHttpHost = new HttpHost(requestHost, requestPort);
        Socket outsocket;
        outsocket = m_sockFact.createSocket(requestHttpHost.getHostName(),
                                            requestHttpHost.getPort());

        DefaultHttpClientConnection targetConn = new DefaultHttpClientConnection();
        byte[] resbody = new byte[0];
        try {
            try {
                targetConn.bind(outsocket, m_httpparams);
            }
            catch (IOException e) {
                System.err.println("[!] Failed to create connection to destination host");
                return resbody;
            }

            log.info("Connecting to " + outsocket.getInetAddress());

            removeHopByHopHeaders(request);
            // don't let servers send back zipped content
            request.removeHeaders("Accept-Encoding");

            HttpResponse targetResponse;
            m_httpexec.preProcess(request, m_outhttpproc, context);
            targetResponse = m_httpexec.execute(request, targetConn, context);
            m_httpexec.postProcess(response, m_outhttpproc, context);

            resbody = copyResponse(targetResponse, response);

            logHeaders(response);
        }
        finally {
            try {
                targetConn.close();
            }
            catch (IOException ignore) {
            }
        }

        return resbody;
    }


    /**
     * Copies all response headers and body from 'from' to 'to'
     * @param from
     * @param to
     * @return the body of the 'from' response
     * @throws IOException
     */
    private byte[] copyResponse(HttpResponse from, HttpResponse to) throws IOException {
        removeHopByHopHeaders(from);

        to.setStatusLine(from.getStatusLine());
        to.setHeaders(from.getAllHeaders());
        byte[] resbody = getResponseBody(from);

        // re-wrap the response body ...
        setResponseEntity(to, resbody, from.getFirstHeader("Content-Type"));

        return resbody;
    }

    /**
     * @param response
     * @return the body of response in byte array
     * @throws IOException
     */
    private byte[] getResponseBody(HttpResponse response) throws IOException
    {
        HttpEntity responseEntity = response.getEntity();
        if (responseEntity == null)
            return new byte[0];

        return EntityUtils.toByteArray(responseEntity);
    }

    /**
     * sets the response entity to be the passed body
     * @param response
     * @param body
     * @param contentType
     * @throws IOException
     */
    private void setResponseEntity(HttpResponse response, byte[] body, Header contentType) throws IOException
    {
        byte[] resBody = body.clone();

        BasicHttpEntity entity = new BasicHttpEntity();
        InputStream stream = new ByteArrayInputStream(resBody);
        entity.setContentLength(stream.available());
        entity.setContent(stream);
        entity.setContentType(contentType);
        response.setEntity(entity);
    }

    /**
     * @param entry
     * @return true if the cache entry stored is still valid and can be used.
     * @throws UnknownHostException
     * @throws IOException
     * @throws HttpException
     */
    private HttpResponse validateCacheEntry(ProxyCache.CachedItem entry)
        throws UnknownHostException, IOException, HttpException
    {
        //performRequest(request, response, context)

        HttpContext context = new BasicHttpContext(null);

        BasicHttpRequest request = new BasicHttpRequest("GET", entry.m_url);
        HttpResponse response = new BasicHttpResponse(HttpVersion.HTTP_1_1, 404, "Error");

        String lastmodified = "";
        for (Header h : entry.m_headers) {
            if (h.getName().equals(HttpHeaders.LAST_MODIFIED)) {
                lastmodified = h.getValue();
                break;
            }
        }
        log.info("cached last modified: " + lastmodified);

        request.setParams(m_httpparams);
        request.addHeader(HttpHeaders.IF_MODIFIED_SINCE, lastmodified);
        request.addHeader(HttpHeaders.CONNECTION, "close");
        request.addHeader(HttpHeaders.HOST, getHost(entry.m_url));

        performRequest(request, response, context);

        return response;
    }

    /**
     * @param request
     * @param response
     * @return true if the response can be cached.
     */
    private boolean isCacheable(HttpRequest request, HttpResponse response)
    {
        Header[] transferHeaders = response.getHeaders(HTTP.TRANSFER_ENCODING);
        for (Header h : transferHeaders)
            if (h.getValue().equals("chunked"))
                return false;
        Header[] cacheHeaders = response.getHeaders("Cache-Control");
        for (Header h1 : cacheHeaders) {
            String hval = h1.getValue();
            if (hval.equals("no-store") || hval.equals("no-cache"))
                return false;
        }
        String requestMethod = request.getRequestLine().getMethod()
            .toUpperCase(Locale.ENGLISH);
        return requestMethod.equals("GET")
               && response.getStatusLine().getStatusCode() == 200
               && response.getFirstHeader("Last-Modified") != null;
    }

    /**
     * @param url
     * @return port, default port if none is present in the url
     */
    private int getPort(String url)
    {
        URI tmp;
        try {
            tmp = new URI(url);
        }
        catch (URISyntaxException e) {
            return DEFAULT_PORT;
        }

        int port = tmp.getPort();
        if (port == -1)
            return DEFAULT_PORT;
        else
            return port;
    }

    /**
     * @param url
     * @return the host portion of the url
     */
    private String getHost(String url)
    {
        URI tmp;
        try {
            tmp = new URI(url);
        }
        catch (URISyntaxException e) {
            return "";
        }

        String host = tmp.getHost();
        if (host == null)
            return "";
        else
            return host;
    }

    /**
     * remove headers that aren't needed when using a proxy
     * @param msg
     */
    private void removeHopByHopHeaders(HttpMessage msg)
    {
        msg.removeHeaders(HTTP.CONTENT_LEN);
        msg.removeHeaders(HTTP.TRANSFER_ENCODING);
        msg.removeHeaders(HTTP.CONN_DIRECTIVE);
        msg.removeHeaders("Keep-Alive");
        msg.removeHeaders("TE");
        msg.removeHeaders("Trailers");
        msg.removeHeaders("Upgrade");
        msg.removeHeaders("Proxy-Authenticate");
    }

    private void logHeaders(HttpMessage httpmsg)
    {
        if (httpmsg == null)
            return;

        HeaderIterator hi = httpmsg.headerIterator();
        StringBuilder headers = new StringBuilder();
        if (httpmsg instanceof HttpRequest)
            headers.append("Request Headers:\n");
        else if (httpmsg instanceof HttpResponse)
            headers.append("Response Headers:\n");
        else
            headers.append("Headers of unkown type of HttpMessage");
        while (hi.hasNext())
            headers.append("\t" + hi.next().toString() + "\n");
        log.info(headers.toString());
    }
}
