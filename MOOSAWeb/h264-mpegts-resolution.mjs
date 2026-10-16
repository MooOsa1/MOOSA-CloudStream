/**
 * Read REAL H.264 frame dimensions from a bounded MPEG-TS sample.
 *
 * Used as a second guard beyond HLS EXT-X-STREAM-INF declarations: upstream
 * servers may advertise HD while their actual encoded video is 480p.
 * No external packages, FFmpeg process, credentials, DRM or network access.
 * Returns null for unsupported HEVC/fMP4, malformed or insufficient media.
 */
function bitReader(bytes){
  let bit=0;
  function read(n){
    if(!Number.isInteger(n)||n<0||n>32||bit+n>bytes.length*8)
      throw Error('truncated SPS');
    let value=0;
    for(let i=0;i<n;i++){
      value=value*2+((bytes[bit>>3]>>(7-(bit&7)))&1);
      bit++;
    }
    return value;
  }
  return {
    read,
    ue(){
      let leading=0;
      while(read(1)===0){
        if(++leading>30)throw Error('invalid Exp-Golomb');
      }
      return (2**leading-1)+read(leading);
    },
    se(){
      const v=this.ue();
      return v%2===0?-(v/2):(v+1)/2;
    }
  };
}
export function readH264SpsDimensions(nal){
  try{
    if(!(nal instanceof Uint8Array)||nal.length<8||nal.length>4096||
       (nal[0]&31)!==7)return null;
    const unescaped=[];
    for(let i=1;i<nal.length;i++){
      if(i>=3&&nal[i]===3&&nal[i-1]===0&&nal[i-2]===0)continue;
      unescaped.push(nal[i]);
    }
    const b=bitReader(Uint8Array.from(unescaped));
    const profile=b.read(8);
    b.read(8); // constraint flags
    b.read(8); // level
    b.ue(); // seq_parameter_set_id
    let chroma=1,separatePlane=0;
    if([44,83,86,100,110,118,122,128,134,135,138,139,244].includes(profile)){
      chroma=b.ue();
      if(chroma>3)return null;
      if(chroma===3)separatePlane=b.read(1);
      b.ue();b.ue();b.read(1); // 2 bit depths, qpprime
      if(b.read(1)){
        // Scaling matrices need full decoding; fail closed instead of
        // accidentally reading wrong cropping/dimension bits.
        return null;
      }
    }
    b.ue(); // log2_max_frame_num_minus4
    const poc=b.ue();
    if(poc===0)b.ue();
    else if(poc===1){
      b.read(1);b.se();b.se();
      const count=b.ue();
      if(count>255)return null;
      for(let i=0;i<count;i++)b.se();
    }else if(poc>2)return null;
    b.ue();b.read(1); // max_num_ref_frames, gaps_in_frame_num_value_allowed_flag
    const mbWidth=b.ue()+1,mbHeight=b.ue()+1;
    if(mbWidth>512||mbHeight>512)return null;
    const progressive=b.read(1);
    if(!progressive)b.read(1);
    b.read(1); // direct_8x8_inference_flag
    let left=0,right=0,top=0,bottom=0;
    if(b.read(1)){
      left=b.ue();right=b.ue();top=b.ue();bottom=b.ue();
    }
    const chromaArray=separatePlane?0:chroma;
    const cropX=chromaArray===0||chromaArray===3?1:2;
    const cropY=(chromaArray===1?2:1)*(2-progressive);
    const width=mbWidth*16-(left+right)*cropX;
    const height=mbHeight*16*(2-progressive)-(top+bottom)*cropY;
    if(!Number.isInteger(width)||!Number.isInteger(height)||
       width<160||height<120||width>8192||height>4320)return null;
    return {width,height,codec:'avc'};
  }catch{return null;}
}
function annexBResolution(packets){
  const size=packets.reduce((n,p)=>n+p.length,0);
  if(!size||size>262144)return null;
  const bytes=new Uint8Array(size);
  let pos=0;
  for(const p of packets){bytes.set(p,pos);pos+=p.length;}
  const starts=[];
  for(let i=0;i<size-4;i++){
    if(bytes[i]!==0||bytes[i+1]!==0)continue;
    const n=bytes[i+2]===1?3:
      bytes[i+2]===0&&bytes[i+3]===1?4:0;
    if(n){starts.push(i+n);i+=n-1;}
    if(starts.length>200)return null;
  }
  for(let i=0;i<starts.length;i++){
    const from=starts[i];
    if((bytes[from]&31)!==7)continue;
    const end=i+1<starts.length?starts[i+1]-3:Math.min(size,from+4096);
    const nal=bytes.subarray(from,Math.min(end,from+4096));
    const dims=readH264SpsDimensions(nal);
    if(dims)return dims;
  }
  return null;
}
export function getH264ResolutionFromMpegTs(bytes){
  if(!(bytes instanceof Uint8Array)||bytes.length<188||
     bytes.length>524288||bytes[0]!==0x47)return null;
  let videoPid=-1,readBytes=0;
  const chunks=[];
  for(let start=0;start+188<=bytes.length;start+=188){
    if(bytes[start]!==0x47)break;
    const second=bytes[start+1],control=(bytes[start+3]>>4)&3;
    if(control===0||control===2)continue; // no payload
    const pid=((second&31)<<8)|bytes[start+2];
    let pos=start+4;
    if(control===3){
      pos+=1+bytes[pos];
      if(pos>=start+188)continue;
    }
    if(second&0x40){
      if(pos+9>start+188)continue;
      const stream=bytes[pos+3];
      if(bytes[pos]===0&&bytes[pos+1]===0&&bytes[pos+2]===1&&
         stream>=0xe0&&stream<=0xef){
        if(videoPid!==pid){videoPid=pid;chunks.length=0;readBytes=0;}
        const head=9+bytes[pos+8];
        pos+=head;
      }else if(videoPid===pid)continue;
    }
    if(pid!==videoPid||pos>=start+188)continue;
    const payload=bytes.subarray(pos,start+188);
    chunks.push(payload);readBytes+=payload.length;
    if(readBytes>262144)break;
    // Once enough video data has arrived, parse its SPS.
    if(readBytes>=512){
      const dims=annexBResolution(chunks);
      if(dims)return dims;
    }
  }
  return chunks.length?annexBResolution(chunks):null;
}
