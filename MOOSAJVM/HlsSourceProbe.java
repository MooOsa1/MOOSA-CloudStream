import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bound, read-only live media gate for the license-approved Sintel test.
 *
 * Does not send Referer, Cookie, Authorization, DRM data, or secret headers.
 * Does not follow redirects or proxy media. Only permits one reviewed public
 * hostname observed from the original CineStream .cs3. Other addresses are
 * rejected BEFORE touching the network. Never logs tokens or complete URLs.
 */
public final class HlsSourceProbe {
    private static final String SAFE_HOST="visionaryfounderslab.site";
    private static final String ORIGIN="https://moosa-web.onrender.com";
    private static final int MAX_PLAYLIST=120_000;
    private static final HttpClient HTTP=HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    private record Result(int status,String contentType,String cors,String body) {}
    private static URI checked(URI uri) {
        if(!"https".equalsIgnoreCase(uri.getScheme())||!SAFE_HOST.equalsIgnoreCase(uri.getHost())||
            uri.getUserInfo()!=null||uri.getPort()!=-1||uri.getFragment()!=null)
            throw new IllegalArgumentException("untrusted_media_host");
        return uri;
    }
    private static Result get(URI uri,int limit, boolean range) throws Exception {
        checked(uri);
        HttpRequest.Builder builder=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
            .header("Origin",ORIGIN).header("Accept","application/vnd.apple.mpegurl, */*").GET();
        if(range)builder.header("Range","bytes=0-2047");
        HttpResponse<InputStream> response=HTTP.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());
        try(InputStream stream=response.body()){
            byte[] payload=stream.readNBytes(limit);
            return new Result(response.statusCode(),
                response.headers().firstValue("content-type").orElse(""),
                response.headers().firstValue("access-control-allow-origin").orElse(""),
                new String(payload,java.nio.charset.StandardCharsets.ISO_8859_1));
        }
    }
    private static URI nextMedia(Result playlist,URI base) {
        if(!playlist.body.startsWith("#EXTM3U"))throw new IllegalStateException("not_hls");
        if(playlist.body.contains("#EXT-X-KEY:")||playlist.body.contains("#EXT-X-SESSION-KEY:"))
            throw new IllegalStateException("drm_or_encrypted_hls");
        for(String raw:playlist.body.split("\\r?\\n")) {
            String text=raw.trim();
            if(text.isBlank()||text.startsWith("#"))continue;
            return checked(base.resolve(text));
        }
        throw new IllegalStateException("no_child_media");
    }
    private static boolean hasCors(Result r) {
        return "*".equals(r.cors)||ORIGIN.equals(r.cors);
    }
    public static void probe(List<String> candidates) {
        int n=0;
        for(String text:candidates){
            if(n>=3)break;
            n++;
            int masterStatus=0,childStatus=0,segmentStatus=0;
            boolean masterHls=false,firstSegmentVideo=false,segmentCors=false,encrypted=false;
            String error="none";
            try{
                URI uri=checked(URI.create(text));
                if(!uri.getPath().toLowerCase(Locale.ROOT).endsWith(".m3u8"))
                    throw new IllegalArgumentException("not_hls");
                Result master=get(uri,MAX_PLAYLIST,false);
                masterStatus=master.status;
                masterHls=master.body.startsWith("#EXTM3U");
                if(master.status!=200||!masterHls)throw new IllegalStateException("master_not_readable");
                URI child=nextMedia(master,uri);
                Result rendition;
                URI segmentUrl;
                if(child.getPath().toLowerCase(Locale.ROOT).endsWith(".m3u8")){
                    rendition=get(child,MAX_PLAYLIST,false);
                    childStatus=rendition.status;
                    if(rendition.status!=200)throw new IllegalStateException("variant_not_readable");
                    segmentUrl=nextMedia(rendition,child);
                }else{
                    segmentUrl=child;
                    childStatus=-1;
                }
                // Do not ever fetch a remote key, bearer token or DRM URL.
                if(!(segmentUrl.getPath().toLowerCase(Locale.ROOT).matches(".*\\.(ts|m4s|mp4|m4v|aac)")))
                    throw new IllegalStateException("segment_ext_not_allowed");
                Result segment=get(segmentUrl,2048,true);
                segmentStatus=segment.status;
                segmentCors=hasCors(segment);
                byte[] bytes=segment.body.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
                firstSegmentVideo=(segment.status==200||segment.status==206)
                    &&bytes.length>12
                    &&(bytes[0]==0x47||segment.body.substring(0,Math.min(80,segment.body.length()))
                        .contains("ftyp")||segment.body.substring(0,Math.min(80,segment.body.length())).contains("styp"));
            }catch(Exception e){error=e.getClass().getSimpleName()+":"+e.getMessage();
                error=error.replaceAll("[^a-zA-Z0-9 _:-]","_");
                error=error.substring(0,Math.min(error.length(),85));
            }
            System.out.println("JVM_MEDIA_DIRECT_VERIFY sample="+n+
                " masterStatus="+masterStatus+" masterHls="+masterHls+
                " variantStatus="+childStatus+" segmentStatus="+segmentStatus+
                " segmentVideo="+firstSegmentVideo+" segmentCors="+segmentCors+
                " error="+error);
        }
    }
}
