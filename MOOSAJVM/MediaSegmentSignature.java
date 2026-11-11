/**
 * Bounded HLS first-segment signature check.
 *
 * This is only a container plausibility check, NOT a decode, proof of video
 * dimensions, rights verification or permission to fetch protected content.
 * Fail closed on short, ambiguous and HTML/error responses.
 */
public final class MediaSegmentSignature {
    private MediaSegmentSignature() {}
    private static int u8(byte[] b,int i){return b[i]&0xff;}
    private static long u32(byte[] b,int i){
        return ((long)u8(b,i)<<24)|((long)u8(b,i+1)<<16)|
               ((long)u8(b,i+2)<<8)|u8(b,i+3);
    }
    private static boolean box(byte[] b,int i,String name){
        if(i+8>b.length)return false;
        for(int k=0;k<4;k++)if(u8(b,i+4+k)!=name.charAt(k))return false;
        return true;
    }
    private static boolean ts(byte[] b){
        // MPEG-TS synchronizes at precisely 188-byte packet boundaries.
        // Checking only the first byte (=0x47) admitted arbitrary text.
        if(b.length<188*3)return false;
        for(int i=0;i<188*3;i+=188) {
            if(u8(b,i)!=0x47)return false;
            if((u8(b,i+3)&0x30)==0)return false; // reserved adaptation control
        }
        return true;
    }
    private static boolean mp4(byte[] b){
        if(b.length<16)return false;
        long size=u32(b,0);
        if(size<16||size>256L*1024*1024)return false;
        if(box(b,0,"ftyp")||box(b,0,"styp")){
            // ftyp/styp followed by a four-byte major_brand.
            for(int i=8;i<12;i++){
                int c=u8(b,i);
                if(!((c>=0x30&&c<=0x39)||(c>=0x41&&c<=0x5a)||
                     (c>=0x61&&c<=0x7a)||c==0x20))return false;
            }
            return true;
        }
        // Some legal fMP4 media segments begin directly with moof.
        // Verify its nested first box is a plausible mfhd header.
        if(box(b,0,"moof")){
            if(!box(b,8,"mfhd")||b.length<24)return false;
            long mfhdSize=u32(b,8);
            return mfhdSize>=16&&mfhdSize<=size-8;
        }
        return false;
    }
    public static boolean isLikelyVideo(byte[] sample){
        return sample!=null&&(ts(sample)||mp4(sample));
    }
}
