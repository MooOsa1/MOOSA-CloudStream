import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * Experimental metadata repair for a checksum-verified native CineStream .cs3.
 * The original CineStreamProvider.load() currently drops a populated TMDB ID
 * on the ordinary IMDb movie path. This restores it only when a trusted
 * metadata document matches the exact IMDb ID AND title. No guessing.
 *
 * Pure JSON repair is independently testable; live lookup is limited to the
 * single licensed Sintel smoke test and a fixed public metadata host.
 */
public final class CinemetaIdentity {
    private CinemetaIdentity(){}
    private static final String TEST_IMDB="tt1727587";
    private static final String TEST_TITLE="Sintel";
    private static final int MAX_JSON=65_536;
    public static String restore(String original,String cinemeta,
                                 String imdb,String title){
        try{
            JSONObject input=new JSONObject(original);
            if(!imdb.equals(input.optString("id"))||
               !title.equalsIgnoreCase(input.optString("title")))return original;
            // Preserve valid ID supplied by upstream plugin. Do not overwrite it.
            if(input.optInt("tmdbId",0)>0)return original;
            JSONObject meta=new JSONObject(cinemeta).optJSONObject("meta");
            if(meta==null)return original;
            String foundImdb=meta.optString("imdb_id","");
            if(foundImdb.isBlank())foundImdb=meta.optString("id","");
            if(!imdb.equals(foundImdb)||!title.equalsIgnoreCase(meta.optString("name","")))
                return original;
            int found=meta.optInt("moviedb_id",0);
            if(found<=0||found>99_999_999)return original;
            input.put("tmdbId",found);
            return input.toString();
        }catch(Exception ignored){return original;}
    }
    public static String enrichLicensedSintel(String original){
        try{
            JSONObject input=new JSONObject(original);
            if(!TEST_IMDB.equals(input.optString("id"))||
               !TEST_TITLE.equalsIgnoreCase(input.optString("title",""))||
               input.optInt("tmdbId",0)>0)return original;
            HttpClient http=HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .followRedirects(HttpClient.Redirect.NEVER).build();
            URI uri=URI.create("https://v3-cinemeta.strem.io/meta/movie/tt1727587.json");
            HttpRequest request=HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<InputStream> response=http.send(request,
                HttpResponse.BodyHandlers.ofInputStream());
            try(InputStream stream=response.body()){
                if(response.statusCode()!=200)return original;
                byte[] bytes=stream.readNBytes(MAX_JSON+1);
                if(bytes.length>MAX_JSON)return original;
                return restore(original,new String(bytes,StandardCharsets.UTF_8),
                    TEST_IMDB,TEST_TITLE);
            }
        }catch(Exception ignored){return original;}
    }
    public static int tmdbId(String json){
        try{return new JSONObject(json).optInt("tmdbId",0);}
        catch(Exception e){return 0;}
    }
}
