import org.json.JSONObject;

/** Pure, deterministic tests: upstream data must match both title and IMDb. */
public final class CinemetaIdentityTest {
    private static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    private static String plugin(int tmdb,String id,String title){
        JSONObject data=new JSONObject().put("id",id).put("title",title);
        if(tmdb==0)data.put("tmdbId",JSONObject.NULL);
        else data.put("tmdbId",tmdb);
        return data.toString();
    }
    private static String metadata(String imdb,String title,int tmdb){
        return new JSONObject().put("meta",new JSONObject()
            .put("id",imdb).put("imdb_id",imdb)
            .put("name",title).put("moviedb_id",tmdb)).toString();
    }
    public static void main(String[] ignored){
        String input=plugin(0,"tt1727587","Sintel");
        String verified=metadata("tt1727587","Sintel",45745);
        String repaired=CinemetaIdentity.restore(input,verified,"tt1727587","Sintel");
        check(CinemetaIdentity.tmdbId(repaired)==45745,"restores verified TMDB ID");
        check(CinemetaIdentity.restore(input,metadata("tt0000001","Sintel",45745),
            "tt1727587","Sintel").equals(input),"reject IMDb mismatch");
        check(CinemetaIdentity.restore(input,metadata("tt1727587","Fake Sintel",45745),
            "tt1727587","Sintel").equals(input),"reject title mismatch");
        check(CinemetaIdentity.restore(input,"broken response","tt1727587","Sintel").equals(input),
            "reject malformed metadata");
        check(CinemetaIdentity.restore(input,metadata("tt1727587","Sintel",0),
            "tt1727587","Sintel").equals(input),"reject zero TMDB ID");
        String existing=plugin(444,"tt1727587","Sintel");
        check(CinemetaIdentity.restore(existing,verified,"tt1727587","Sintel").equals(existing),
            "preserve valid upstream ID");
        String wrong=plugin(0,"tt5555555","Sintel");
        check(CinemetaIdentity.restore(wrong,verified,"tt1727587","Sintel").equals(wrong),
            "never repair a different movie");
        check(CinemetaIdentity.restore(input,verified,"tt1727587","Not Sintel").equals(input),
            "verify requested title");
        check(CinemetaIdentity.restore(input,metadata("tt1727587","Sintel",999999999),
            "tt1727587","Sintel").equals(input),"reject excessive TMDB number");
        System.out.println("MOOSA_JVM_CINEMETA_IDENTITY_TEST status=passed cases=9");
    }
}
