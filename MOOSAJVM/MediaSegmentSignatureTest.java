import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** No network, no real media; deterministic signatures and false-positive regressions. */
public final class MediaSegmentSignatureTest {
    private static void check(boolean value,String message){
        if(!value)throw new AssertionError(message);
    }
    private static void write32(byte[] b,int offset,int value){
        b[offset]=(byte)(value>>>24);b[offset+1]=(byte)(value>>>16);
        b[offset+2]=(byte)(value>>>8);b[offset+3]=(byte)value;
    }
    private static void ascii(byte[] b,int offset,String text){
        byte[] bytes=text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes,0,b,offset,bytes.length);
    }
    public static void main(String[] args){
        byte[] ts=new byte[188*3];
        for(int i=0;i<ts.length;i+=188){ts[i]=0x47;ts[i+3]=0x10;}
        check(MediaSegmentSignature.isLikelyVideo(ts),"real TS packet pattern");
        byte[] onlyFirstByte=Arrays.copyOf(ts,ts.length);
        onlyFirstByte[188]=0;
        check(!MediaSegmentSignature.isLikelyVideo(onlyFirstByte),"single G byte is not video");
        check(!MediaSegmentSignature.isLikelyVideo("GET /not-a-video".getBytes(StandardCharsets.US_ASCII)),
            "HTTP text starting with G must fail");
        check(!MediaSegmentSignature.isLikelyVideo(
            "<html><body>ftyp</body></html>".getBytes(StandardCharsets.US_ASCII)),
            "HTML with ftyp text must fail");
        byte[] shortTs=Arrays.copyOf(ts,188*2);
        check(!MediaSegmentSignature.isLikelyVideo(shortTs),"ambiguous two packets must fail");
        byte[] mp4=new byte[32];write32(mp4,0,24);ascii(mp4,4,"ftyp");ascii(mp4,8,"isom");
        check(MediaSegmentSignature.isLikelyVideo(mp4),"valid MP4 ftyp box");
        byte[] fmp4=Arrays.copyOf(mp4,mp4.length);ascii(fmp4,4,"styp");
        check(MediaSegmentSignature.isLikelyVideo(fmp4),"valid fMP4 styp box");
        byte[] badBox=Arrays.copyOf(mp4,mp4.length);write32(badBox,0,8);
        check(!MediaSegmentSignature.isLikelyVideo(badBox),"invalid box length");
        byte[] badBrand=Arrays.copyOf(mp4,mp4.length);badBrand[8]='<';
        check(!MediaSegmentSignature.isLikelyVideo(badBrand),"invalid MP4 brand");
        byte[] moof=new byte[48];write32(moof,0,48);ascii(moof,4,"moof");
        write32(moof,8,16);ascii(moof,12,"mfhd");
        check(MediaSegmentSignature.isLikelyVideo(moof),"valid moof/mfhd");
        byte[] fakeMoof=Arrays.copyOf(moof,moof.length);ascii(fakeMoof,12,"junk");
        check(!MediaSegmentSignature.isLikelyVideo(fakeMoof),"invalid moof child");
        check(!MediaSegmentSignature.isLikelyVideo(null),"null sample must fail");
        System.out.println("MOOSA_JVM_MEDIA_SIGNATURE_TEST status=passed");
    }
}
