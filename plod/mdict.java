package plod;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Adler32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.InflaterOutputStream;

import org.jvcompress.lzo.MiniLZO;
import org.jvcompress.util.MInt;

import rbtree.RBTree;
import rbtree.RBTree_additive;


/**
 * Mdict Java Library
 * FEATURES:
 * *********Basic parse and query functions.
 * *********Advanced mdicts conjunction search.
 * *********Search all text.(multithread)
 * *********Match middle string of any entry.
 * @author KnIfER
 * @date 2017/12/30
 */

public class mdict {

/*    
    推荐文本编辑器：notepad++，使用 alt+2 技能键获得全局观~ 其他技能键：双击近战，ctrl+f洲际导弹。
    
    usage : getRecordAt(lookUp("happy"))
    
    笔记(给自己看的):initialize mdx 得到 _key_block_info_list 。相关文件部分有4个组成: headerKeyText (s)、 tailerKeyText (s)、 key_block_compressed_size (s)、 key_block_decompressed_size (s)
    
    *lookUp("key") SUMMARY: lookUp returns the position of best-matched entry to "key".
            first,根据“根据头部信息建立起来的Red-Black树： block_blockId_search_tree ”,使用我写的二叉树搜索算法xxing,获得最近匹配项（即最相近的块起始单词）所在的block id.
            second,根据 _key_block_info_list[block id] 存储的信息,将第block id个key_block解压出来。这事获得2000个词条左右的数组，一并放入_key_block_info_list[block id].key_list 中。
            最后调用 binary_find_closest2 ,作用于 key_list,得最近匹配项相对于key_list的位置，然后根据 num_entries_accumulator 换算得到最近匹配项相对于第一个入口词条的位置。
            
    *getEntryAt(pos): returns key_Text at position "pos".主要用于安卓listview adapter的实现
    *getRecordAt(pos): SUMMARY: returns the no.pos entry's Contents.
    
    [0]file header
    [0]key_block_info section 有4个组成
    根据头部信息建立 Red-Black树： block_blockId_search_tree ,结点形式：[key_block块i起始单词（shrinked text）,i] 且树根据起始单词的String排序建立。
    [0]key_block section:该文件部分存储所有入口词条。
    [2]record_block_info section
    Decode_record_block_header,根据头部信息建立 Red-Black树： accumulation_RecordB_tree ,
        结点形式：[ decompressed_size_accumulator,i] 且树根据decompressed_size_accumulator的int值大小排序建立。
        decompressed_size_accumulator:Record_block块i之前所有Record_block解压后的大小
    [2]record_block section:存储所有词条的具体内容。  
    
   NOTE: 红黑树是不必要的，接下来我会建立分支，撤销树的构建。
   NOTE: R-B tree is not necessary when parsing key info,binary search tree is suffice,but..

*/
    
    private final static String replaceReg = " |:|\\.|,|-|\'";
    private final static String emptyStr = "";
    final static byte[] _zero4 = new byte[]{0,0,0,0};
    final static byte[] _1zero3 = new byte[]{1,0,0,0};
    final static byte[] _2zero3 = new byte[]{2,0,0,0};
    
	private key_info_struct[] _key_block_info_list;
	private byte[] _key_block_compressed;
	private record_info_struct[] _record_info_struct_list;
	RBTree<myCpr<Integer, Integer>> accumulation_blockId_tree = new RBTree<myCpr<Integer, Integer>>();
    private RBTree<myCpr<Long   , Integer>> accumulation_RecordB_tree = new RBTree<myCpr<Long   , Integer>>();
    RBTree<myCpr<String , Integer>> block_blockId_search_tree = new RBTree<myCpr<String , Integer>>();

    private File f;
    private String _Dictionary_fName;
    private String _Dictionary_Name;
    private int _encrypt=0;
	private int _number_width;
	 String _encoding=emptyStr;
	private String _passcode = emptyStr;
	private HashMap<Integer,String[]> _stylesheet = new HashMap<Integer,String[]>();
	private float _version;
	private long _num_entries;public long getNumberEntries(){return _num_entries;}
	private long _num_key_blocks;
    private long _num_record_blocks;
    private long accumulation_blockId_tree_TIME = 0;
    private long block_blockId_search_tree_TIME = 0;
    private long _key_block_offset;
    private long _record_block_offset;
    private HashMap<String,String> _header_tag;
    
    public class myCpr<T1 extends Comparable<T1>,T2> implements Comparable<myCpr<T1,T2>>{
    	public T1 key;
    	public T2 value;
    	public myCpr(T1 k,T2 v){
    		key=k;value=v;
    	}
    	public int compareTo(myCpr<T1,T2> other) {
    		return this.key.compareTo(other.key);
    	}
    	public String toString(){
    		return key+"_"+value;
    	}
    }
    //store key_block's summary and itself
    public class key_info_struct{
		public key_info_struct(String headerKeyText, String tailerKeyText,
    			long key_block_compressed_size_accumulator,
    			long key_block_decompressed_size) {
    		this.headerKeyText=headerKeyText;
    		this.tailerKeyText=tailerKeyText;		
    		this.key_block_compressed_size_accumulator=key_block_compressed_size_accumulator;		
    		this.key_block_decompressed_size=key_block_decompressed_size;		
    	}
    	public key_info_struct(long num_entries_,long num_entries_accumulator_) {
    		num_entries=num_entries_;
    		num_entries_accumulator=num_entries_accumulator_;
        }
		public String headerKeyText;
    	public String tailerKeyText;
    	public long key_block_compressed_size_accumulator;
    	public long key_block_compressed_size;
    	public long key_block_decompressed_size;
        public long num_entries;
        public long num_entries_accumulator;
        public String[] keys;
        public long[] key_offsets;
        public void ini(){
            keys =new String[(int) num_entries];
            key_offsets =new long[(int) num_entries];
        }
    }
    //store record_block's summary
    public class record_info_struct{
    	public record_info_struct(long _compressed_size,long _compressed_size_accumulator,long _decompressed_size,long _decompressed_size_accumulator) {
    		 compressed_size=                  _compressed_size;
             compressed_size_accumulator=      _compressed_size_accumulator;
             decompressed_size=                _decompressed_size;
             decompressed_size_accumulator=    _decompressed_size_accumulator;
        
    	}
        public long compressed_size;
        public long compressed_size_accumulator;
    	public long decompressed_size;
    	public long decompressed_size_accumulator;
        public void ini(){
        }
    }	
	
	byte[] _fast_decrypt(byte[] data,byte[] key){ 
	    long previous = 0x36;
	    for(int i=0;i<data.length;i++){
	    	//INCONGRUENT CONVERTION FROM byte to int
	    	int ddd = data[i]&0xff;
	    	long t = (ddd >> 4 | ddd << 4) & 0xff;
	        t = t ^ previous ^ (i & 0xff) ^ (key[(i % key.length)]&0xff);
	        previous = ddd;
	        data[i] = (byte) t;
        }
	    return data;
    }
	
	byte[] _mdx_decrypt(byte[] comp_block) throws IOException{
		ByteArrayOutputStream data = new ByteArrayOutputStream() ;
		data.write(comp_block,4,4);
		data.write(ripemd128.packIntLE(0x3695));
	    byte[]  key = ripemd128.ripemd128(data.toByteArray());
	    data.reset();
	    data.write(comp_block,0,8);
	    byte[] comp_block2 = new byte[comp_block.length-8];
	    System.arraycopy(comp_block, 8, comp_block2, 0, comp_block.length-8);
	    data.write(_fast_decrypt(comp_block2, key));
	    return data.toByteArray();
    }

    //构造
    //这么长，我也不想啊。但大多数代码和python代码保持一致。不一致的用 INCONGRUENT 标明。
    public mdict(String fn) throws IOException  {
    //![0]File in
    	f = new File(fn);
        _Dictionary_fName = f.getName();
    	DataInputStream data_in =new DataInputStream(new FileInputStream(f));	
    //![1]read_header 
    	// number of bytes of header text
    	byte[] itemBuf = new byte[4];
		data_in.read(itemBuf, 0, 4);
    	int header_bytes_size =getInt(itemBuf[0],itemBuf[1],itemBuf[2],itemBuf[3]);
    	byte[] header_bytes = new byte[header_bytes_size];
    	data_in.read(header_bytes,0, header_bytes_size); 
		// 4 bytes: adler32 checksum of header, in little endian
		itemBuf = new byte[4];
		data_in.read(itemBuf, 0, 4);
    	int alder32 = getInt(itemBuf[3],itemBuf[2],itemBuf[1],itemBuf[0]);
		assert alder32 == (calcChecksum(header_bytes)& 0xffffffff);
		_key_block_offset = 4 + header_bytes_size + 4;
		//不必关闭文件流 data_in
		
		Pattern re = Pattern.compile("(\\w+)=\"(.*?)\"",Pattern.DOTALL);
		Matcher m = re.matcher(new String(header_bytes,"UTF-16LE"));
		_header_tag = new HashMap<String,String>();
		while(m.find()) {
			_header_tag.put(m.group(1), m.group(2));
	      }				
		if(_header_tag.containsKey("Title"))
			_Dictionary_Name=_header_tag.get("Title");
		
		_encoding = _header_tag.get("Encoding");
        // GB18030 > GBK > GB2312
        if(_encoding.equals("GBK")|| _encoding.equals("GB2312"))
        	_encoding = "GB18030";
        if(_encoding.equals("UTF-16"))
        	_encoding = "UTF-16LE"; //INCONGRUENT java charset          
        if (_encoding.equals(emptyStr))
        	_encoding = "UTF-8";
        
        // encryption flag
        //   0x00 - no encryption
        //   0x01 - encrypt record block
        //   0x02 - encrypt key info block
		if(!_header_tag.containsKey("Encrypted") || _header_tag.get("Encrypted").equals("0") || _header_tag.get("Encrypted").equals("No"))
            _encrypt = 0;
		else if(_header_tag.get("Encrypted") == "1")
            _encrypt = 1;
        else
            _encrypt = Integer.valueOf(_header_tag.get("Encrypted"));

        // stylesheet attribute if present takes form of:
        //   style_number # 1-255
        //   style_begin  # or ''
        //   style_end    # or ''
        // store stylesheet in dict in the form of
        // {'number' : ('style_begin', 'style_end')}
        
        if(_header_tag.containsKey("StyleSheet")){
            String[] lines = _header_tag.get("StyleSheet").split("[\r\n \r \n]");
            for(int i=0;i<=lines.length-3;i+=3)
                _stylesheet.put(i,new String[]{lines[i+1],lines[i+2]});
        }
        // version diffenences
        _version = Float.valueOf(_header_tag.get("GeneratedByEngineVersion"));
        if(_version < 2.0)
            _number_width = 4;
        else
            _number_width = 8;
    //![1]HEADER 分析完毕 
    //![2]_read_keys_info START
        //size (in bytes) of previous 5/4 numbers (can be encrypted)
        int num_bytes;
        if(_version >= 2)
            num_bytes = 8 * 5;
        else
            num_bytes = 4 * 4;
		itemBuf = new byte[num_bytes];
		data_in.read(itemBuf, 0, num_bytes);
        ByteBuffer sf = ByteBuffer.wrap(itemBuf);
        
        //TODO: pureSalsa20.py decryption
        if(_encrypt==1){if(_passcode==emptyStr) throw new IllegalArgumentException("_passcode未输入");}
        _num_key_blocks = _read_number(sf);                                           // 1
        _num_entries = _read_number(sf);                                              // 2
        if(_version >= 2.0){long key_block_info_decomp_size = _read_number(sf);}      //[3]
        
        long key_block_info_size = _read_number(sf);                                  // 4
        long key_block_size = _read_number(sf);                                       // 5
        
        //前 5 个数据的 adler checksum
        if(_version >= 2.0)
        {
            int adler32 = calcChecksum(itemBuf);
    		itemBuf = new byte[4];
    		data_in.read(itemBuf, 0, 4);
            assert adler32 == (getInt(itemBuf[0],itemBuf[1],itemBuf[2],itemBuf[3])& 0xffffffff);
        }
        
        // read key block info, which comprises each key_block's:
        //1.(starting && ending words'shrinkedText,in the form of shrinkedTextSize-shrinkedText.name them: headerText、tailerText)、
        //2.(compressed && decompressed size,which also have version differnence,either occupying 4 or 8 bytes)
		itemBuf = new byte[(int) key_block_info_size];
        System.out.println("key_block_info_size ="+key_block_info_size);  
		data_in.read(itemBuf, 0, (int) key_block_info_size);
        _key_block_info_list = _decode_key_block_info(itemBuf);// 根据头部信息建立 Red-Black树： block_blockId_search_tree
        
        assert(_num_key_blocks == _key_block_info_list.length);

        if(_version<2)
            _record_block_offset = _key_block_offset+4 * 4+key_block_info_size+key_block_size;
        else
        	_record_block_offset = _key_block_offset+num_bytes+4+key_block_info_size+key_block_size;
    //![2]_read_keys_info END
        
        System.out.println("\r\n开始打印头部信息 Red-Black 树...");
		for(myCpr<String,Integer> i:block_blockId_search_tree.flatten()){
			System.out.println(i);
		}
        System.out.println();
		//System.out.println(block_blockId_search_tree.sxing(new myCpr("rmt",1)) );
		//System.out.println(block_blockId_search_tree.sxing(new myCpr("rmt",1)).getKey().value );
		System.out.println("accumulation_blockId_tree_TIME 建树时间="+accumulation_blockId_tree_TIME);
		System.out.println("block_blockId_search_tree_TIME 建树时间="+block_blockId_search_tree_TIME); 

		_key_block_compressed = new byte[(int) key_block_size];
		data_in.read(_key_block_compressed, 0, (int) key_block_size);

    //![3]Decode_record_block_header
        DataInputStream data_in1 = new DataInputStream(new FileInputStream(f));
        data_in1.skipBytes((int) _record_block_offset);
        _num_record_blocks = _read_number(data_in1);
        long num_entries = _read_number(data_in1);
        System.out.println(num_entries); 
        System.out.println(_num_entries); 
        assert(num_entries == _num_entries);
        long record_block_info_size = _read_number(data_in1);
        long record_block_size = _read_number(data_in1);
        //record block info section
        _record_info_struct_list = new record_info_struct[(int) _num_record_blocks];
        int size_counter = 0;
        long compressed_size_accumulator = 0;
        long decompressed_size_accumulator = 0;
		for(int i=0;i<_num_record_blocks;i++){
            long compressed_size = _read_number(data_in1);
            long decompressed_size = _read_number(data_in1);
            maxComRecSize = Math.max(maxComRecSize, compressed_size);
            maxDecompressedSize = Math.max(maxDecompressedSize, decompressed_size);
            _record_info_struct_list[i] = new record_info_struct(compressed_size, compressed_size_accumulator, decompressed_size, decompressed_size_accumulator);
            accumulation_RecordB_tree.insert(new myCpr<Long, Integer>(decompressed_size_accumulator,i));
            compressed_size_accumulator+=compressed_size;
            decompressed_size_accumulator+=decompressed_size;
            size_counter += _number_width * 2;
		}
        assert(size_counter == record_block_info_size);
        
        //System.out.println("_num_record_blocks: "+_num_record_blocks);
        //System.out.println("_num_key_blocks: "+_num_key_blocks);
        postIni();
}

    
    
    
    
    
    
    
    private void postIni() {
	record_block = new byte[(int) maxDecompressedSize];		
	}
    long maxComRecSize;
    long maxDecompressedSize;
	private byte[] record_block;
    
    public String getRecordAt(int position) throws IOException {
        int blockId = accumulation_blockId_tree.xxing(new mdict.myCpr(position,1)).getKey().value;
        key_info_struct infoI = _key_block_info_list[blockId];
        long start = infoI.key_block_compressed_size_accumulator;
        long compressedSize;
        prepareItemByKeyInfo(infoI,blockId);
//decode record block
        DataInputStream data_in = new DataInputStream(new FileInputStream(f));
        // record block info section
        data_in.skipBytes( (int) (_record_block_offset+_number_width*4+_num_record_blocks*2*_number_width));
        
        // actual record block data
        int i = (int) (position-infoI.num_entries_accumulator);//处于当前key_info块的第几个
        //infoI.key_offsets[i] 获取Key_ID,即文件偏移
        record_info_struct RinfoI = _record_info_struct_list[accumulation_RecordB_tree.xxing(new mdict.myCpr(infoI.key_offsets[i],1)).getKey().value];
        data_in.skipBytes((int) RinfoI.compressed_size_accumulator);
        //whole section of record_blocks;
       // for(int i123=0; i123<record_block_info_list.size(); i123++){
        	int compressed_size = (int) RinfoI.compressed_size;
        	int decompressed_size = (int) RinfoI.decompressed_size;//用于验证
        	byte[] record_block_compressed = new byte[(int) compressed_size];
        	data_in.read(record_block_compressed);//+8 TODO optimize
            // 4 bytes indicates block compression type
        	byte[] record_block_type = new byte[4];
        	System.arraycopy(record_block_compressed, 0, record_block_type, 0, 4);
        	String record_block_type_str = new String(record_block_type);
        	// 4 bytes adler checksum of uncompressed content
        	ByteBuffer sf1 = ByteBuffer.wrap(record_block_compressed);
            int adler32 = sf1.order(ByteOrder.BIG_ENDIAN).getInt(4);
            //byte[] record_block = new byte[1];
            // no compression
            if(record_block_type_str.equals(new String(new byte[]{0,0,0,0}))){
            	//record_block = new byte[compressed_size-8];
            	System.arraycopy(record_block_compressed, 8, record_block, 0, compressed_size-8);
            }
            // lzo compression
            else if(record_block_type_str.equals(new String(new byte[]{1,0,0,0}))){
                long st=System.currentTimeMillis(); //获取开始时间 
                //record_block = new byte[(int) decompressed_size];
                MInt len = new MInt((int) decompressed_size);
                byte[] arraytmp = new byte[(int) compressed_size];
                System.arraycopy(record_block_compressed, 8, arraytmp, 0,(int) (compressed_size-8));
                MiniLZO.lzo1x_decompress(arraytmp,(int) compressed_size,record_block,len);
            	//System.out.println("get Record LZO decompressing key blocks done!") ;
                //System.out.println("解压Record耗时："+(System.currentTimeMillis()-st));
            }
            // zlib compression
            else if(record_block_type_str.equals(new String(new byte[]{02,00,00,00}))){
               // record_block = zlib_decompress(record_block_compressed,8);
                Inflater inf = new Inflater();
                inf.setInput(record_block_compressed,8,compressed_size-8);
                try {
					int ret = inf.inflate(record_block,0,decompressed_size);
				} catch (DataFormatException e) {
					e.printStackTrace();
				}  				
            }
            // notice not that adler32 return signed value
            //assert(adler32 == (calcChecksum(record_block) ));
            //assert(record_block.length == decompressed_size );
 //当前内容块解压完毕
            
            // split record block according to the offset info from key block
            //String key_text = key_list[i];
            long record_start = infoI.key_offsets[i]-RinfoI.decompressed_size_accumulator;
            long record_end;
            if (i < infoI.num_entries-1){
            	record_end = Long.valueOf(infoI.key_offsets[i+1])-RinfoI.decompressed_size_accumulator; 	
            }
            else{
            	record_end = record_block.length;
            }
            byte[] record = new byte[(int) (record_end-record_start)];         
            System.arraycopy(record_block, (int) (record_start), record, 0, record.length);

            String record_str;
            record_str = new String(record,_encoding); 	
            // substitute styles
            //if self._substyle and self._stylesheet:
            //    record = self._substitute_stylesheet(record);
            return	record_str;           	
    }
    public long t;
    //到底要不要将key entrys存储起来？？
    public void prepareItemByKeyInfo(key_info_struct infoI,int blockId){
        if(infoI.keys==null){
        	long st=System.currentTimeMillis();
            long start = infoI.key_block_compressed_size_accumulator;
            long compressedSize;
        	infoI.ini();
            byte[] key_block = new byte[1];
            if(blockId==_key_block_info_list.length-1)
                compressedSize = _key_block_compressed.length - _key_block_info_list[_key_block_info_list.length-1].key_block_compressed_size_accumulator;
            else
                compressedSize = _key_block_info_list[blockId+1].key_block_compressed_size_accumulator-infoI.key_block_compressed_size_accumulator;
            
            String key_block_compression_type = new String(new byte[]{_key_block_compressed[(int) start],_key_block_compressed[(int) (start+1)],_key_block_compressed[(int) (start+2)],_key_block_compressed[(int) (start+3)]});
            int adler32 = getInt(_key_block_compressed[(int) (start+4)],_key_block_compressed[(int) (start+5)],_key_block_compressed[(int) (start+6)],_key_block_compressed[(int) (start+7)]);
            if(key_block_compression_type.equals(new String(new byte[]{0,0,0,0}))){
                //无需解压
                System.out.println("no compress!");
                key_block = new byte[(int) (_key_block_compressed.length-start-8)];
                System.arraycopy(_key_block_compressed, (int)(start+8), key_block, 0,(int) (_key_block_compressed.length-start-8));
            }else if(key_block_compression_type.equals(new String(new byte[]{1,0,0,0})))
            {
                //key_block = lzo_decompress(_key_block_compressed,(int) (start+_number_width),(int)(compressedSize-_number_width));
            	MInt len = new MInt((int) infoI.key_block_decompressed_size);
            	key_block = new byte[len.v];
                byte[] arraytmp = new byte[(int) compressedSize];
                //show(arraytmp.length+"哈哈哈"+compressedSize);
                System.arraycopy(_key_block_compressed, (int)(start+8), arraytmp, 0,(int) (compressedSize-8));
            	MiniLZO.lzo1x_decompress(arraytmp,arraytmp.length,key_block,len);
                //System.out.println("look up LZO decompressing key blocks done!");
            }
            else if(key_block_compression_type.equals(new String(new byte[]{02,00,00,00}))){
                //key_block = zlib_decompress(_key_block_compressed,(int) (start+8),(int)(compressedSize-8));
                //System.out.println("zip!");
                //System.out.println("zip!");
                Inflater inf = new Inflater();
                inf.setInput(_key_block_compressed,(int) (start+8),(int)(compressedSize-8));
                key_block = new byte[(int) infoI.key_block_decompressed_size];
                try {
					int ret = inf.inflate(key_block,0,(int)(infoI.key_block_decompressed_size));
				} catch (DataFormatException e) {e.printStackTrace();}
                
            }
            //!!spliting curr Key block
            int key_start_index = 0;
            String delimiter;
            int width = 0,i1=0,key_end_index=0;
            int keyCounter = 0;
            ByteBuffer sf = ByteBuffer.wrap(key_block);//must outside of while...
            /*主要耗时步骤
		            主要耗时步骤
		            主要耗时步骤*/
            
            while(key_start_index < key_block.length){
            	long key_id;
            	if(_version<2)
            		key_id = sf.getInt(key_start_index);//Key_ID
            	else
            		key_id = sf.getLong(key_start_index);//Key_ID
                //show("key_id"+key_id);
                if(_encoding.startsWith("UTF-16")){//TODO optimize
                    width = 2;
                    key_end_index = key_start_index + _number_width;  
                    while(i1<key_block.length){
                    	if(key_block[key_end_index]==0 && key_block[key_end_index+1]==0)
                    		break;
                    	key_end_index+=width;
                    }
                }else{
                    width = 1;
                    key_end_index = key_start_index + _number_width;  
                    while(i1<key_block.length){
                    	if(key_block[key_end_index]==0)
                    		break;
                    	key_end_index+=width;
                    }
                }

                //show("key_start_index"+key_start_index);
                byte[] arraytmp = new byte[key_end_index-(key_start_index+_number_width)];
                System.arraycopy(key_block,key_start_index+_number_width, arraytmp, 0,arraytmp.length);
                
                String key_text = null;
				try {
					key_text = new String(arraytmp,_encoding);
				} catch (UnsupportedEncodingException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
                key_start_index = key_end_index + width;
                infoI.keys[keyCounter]=key_text;
                
                infoI.key_offsets[keyCounter]=key_id;
                keyCounter++;
            }
            //System.out.println("耗时"+(st-System.currentTimeMillis()));

            assert(adler32 == (calcChecksum(key_block)));
            //System.out.println("建key表时间"+(e-st));
        }
    }
    
    long[] keyBlocksHeaderTextKeyID;
    //int counter=0;
    public void fetch_keyBlocksHeaderTextKeyID(){
    	int blockId = 0;
    	keyBlocksHeaderTextKeyID = new long[(int)_num_key_blocks];
    	for(key_info_struct infoI:_key_block_info_list){
            long start = infoI.key_block_compressed_size_accumulator;
            long compressedSize;
            byte[] key_block = new byte[1];
            if(blockId==_key_block_info_list.length-1)
                compressedSize = _key_block_compressed.length - _key_block_info_list[_key_block_info_list.length-1].key_block_compressed_size_accumulator;
            else
                compressedSize = _key_block_info_list[blockId+1].key_block_compressed_size_accumulator-infoI.key_block_compressed_size_accumulator;
            
            byte[] key_block_compression_type = new byte[]{_key_block_compressed[(int) start],_key_block_compressed[(int) (start+1)],_key_block_compressed[(int) (start+2)],_key_block_compressed[(int) (start+3)]};
            if(compareByteArrayIsPara(key_block_compression_type, _zero4)){
                //无需解压
                System.out.println("no compress!");
                key_block = new byte[(int) (_key_block_compressed.length-start-8)];
                System.arraycopy(_key_block_compressed, (int)(start+8), key_block, 0,(int) (_key_block_compressed.length-start-8));
            }else if(compareByteArrayIsPara(key_block_compression_type, _1zero3))
            {
                //key_block = lzo_decompress(_key_block_compressed,(int) (start+_number_width),(int)(compressedSize-_number_width));
            	MInt len = new MInt((int) infoI.key_block_decompressed_size);
            	key_block = new byte[len.v];
                byte[] arraytmp = new byte[(int) compressedSize];
                show(arraytmp.length+"哈哈哈"+compressedSize);
                System.arraycopy(_key_block_compressed, (int)(start+8), arraytmp, 0,(int) (compressedSize-8));
            	MiniLZO.lzo1x_decompress(arraytmp,arraytmp.length,key_block,len);
                //System.out.println("look up LZO decompressing key blocks done!");
            }
            else if(compareByteArrayIsPara(key_block_compression_type, _2zero3)){
                key_block = zlib_decompress(_key_block_compressed,(int) (start+8),(int)(compressedSize-8));
                //System.out.println("zip!");
            }
            //!!spliting curr Key block
            //ByteBuffer sf = ByteBuffer.wrap(key_block);//must outside of while...
            /*主要耗时步骤
		            主要耗时步骤
		            主要耗时步骤*/
            
            	if(_version<2)
            		keyBlocksHeaderTextKeyID[blockId] = getInt(key_block[0],key_block[0],key_block[0],key_block[0]);
            	else
            		//keyBlocksHeaderTextKeyID[blockId] = getLong(key_block[0],key_block[1],key_block[2],key_block[3],key_block[4],key_block[5],key_block[6],key_block[7]);
        		keyBlocksHeaderTextKeyID[blockId] = getLong(key_block);

                blockId++;


        }
    }
     
    
    public void printAllKeys(){
    	int blockCounter = 0;
    	for(key_info_struct infoI:_key_block_info_list){
    		prepareItemByKeyInfo(infoI,blockCounter);
    		for(String entry:infoI.keys){
    			show(entry);
    		}
    		show("block no."+(blockCounter++)+"printed");
    	}
    }
    
    public void findAllKeys(String key){
    	int blockCounter = 0;
    	for(key_info_struct infoI:_key_block_info_list){
    		prepareItemByKeyInfo(infoI,blockCounter);
    		for(String entry:infoI.keys){
    			if(entry.contains(key))
    				show(entry);
    		}
    		blockCounter++;
    	}
    }   
    
    public void printRecordInfo() throws IOException{
        for(int i=0; i<_record_info_struct_list.length; i++){
        	record_info_struct RinfoI = _record_info_struct_list[i];
        	show("RinfoI_compressed_size="+RinfoI.compressed_size);
        	
        }	
    }
    
    public void printRecordInfo1() throws IOException{
    	int blockCounter = 0;
    	int prev_ID = 0;
    	int count=0;
    	for(key_info_struct infoI:_key_block_info_list){//遍历所有key_block
    		prepareItemByKeyInfo(infoI,blockCounter);
    		blockCounter++;
    		for(int position=0;position<infoI.keys.length;position++)//遍历所有key_block记录的词条
    		{
	           // int i = (int) (position-infoI.num_entries_accumulator);//处于当前key_info块的第几个
	            //infoI.key_offsets[i] 获取Key_ID,即文件偏移
	    		int RecB_ID = accumulation_RecordB_tree.xxing(new mdict.myCpr(infoI.key_offsets[position],1)).getKey().value;
	            record_info_struct RinfoI = _record_info_struct_list[RecB_ID];
	            if(RecB_ID!=prev_ID){
	            	show(prev_ID+"has entry number:"+count);
	            	count=0;
	            	prev_ID=RecB_ID;
	            }
	            count++;
    		}
    	}
    }    
    
    public void printAllContents() throws IOException{
        DataInputStream data_in = new DataInputStream(new FileInputStream(f));
        // record block info section
        data_in.skipBytes( (int) (_record_block_offset+_number_width*4+_num_record_blocks*2*_number_width));
        
        // actual record block data
        //int i = (int) (position-infoI.num_entries_accumulator);//处于当前record块的第几个
        //record_info_struct RinfoI = _record_info_struct_list[accumulation_RecordB_tree.xxing(new mdict.myCpr(infoI.key_offsets[i],1)).getKey().value];
        
        
        //whole section of record_blocks;
        for(int i=0; i<_record_info_struct_list.length; i++){
        	record_info_struct RinfoI = _record_info_struct_list[i];
        	//data_in.skipBytes((int) RinfoI.compressed_size_accumulator);
        	long compressed_size = RinfoI.compressed_size;
        	long decompressed_size = RinfoI.decompressed_size;//用于验证
        	byte[] record_block_compressed = new byte[(int) compressed_size];
        	data_in.read(record_block_compressed);//+8 TODO optimize
            // 4 bytes indicates block compression type
        	byte[] record_block_type = new byte[4];
        	System.arraycopy(record_block_compressed, 0, record_block_type, 0, 4);
        	String record_block_type_str = new String(record_block_type);
        	// 4 bytes adler checksum of uncompressed content
        	ByteBuffer sf1 = ByteBuffer.wrap(record_block_compressed);
            int adler32 = sf1.order(ByteOrder.BIG_ENDIAN).getInt(4);
            byte[] record_block = new byte[1];
            // no compression
            if(record_block_type_str.equals(new String(new byte[]{0,0,0,0}))){
            	record_block = new byte[(int) (compressed_size-8)];
            	System.arraycopy(record_block_compressed, 8, record_block, 0, record_block.length-8);
            }
            // lzo compression
            else if(record_block_type_str.equals(new String(new byte[]{1,0,0,0}))){
                long st=System.currentTimeMillis(); //获取开始时间 
                record_block = new byte[(int) decompressed_size];
                MInt len = new MInt((int) decompressed_size);
                byte[] arraytmp = new byte[(int) compressed_size];
                System.arraycopy(record_block_compressed, 8, arraytmp, 0,(int) (compressed_size-8));
                MiniLZO.lzo1x_decompress(arraytmp,(int) compressed_size,record_block,len);
            	//System.out.println("get Record LZO decompressing key blocks done!") ;
                //System.out.println("解压Record耗时："+(System.currentTimeMillis()-st));
            }
            // zlib compression
            else if(record_block_type_str.equals(new String(new byte[]{02,00,00,00}))){
                record_block = zlib_decompress(record_block_compressed,8);
            }
            // notice not that adler32 return signed value
            assert(adler32 == (calcChecksum(record_block) ));
            assert(record_block.length == decompressed_size );
 //当前内容块解压完毕
            

            String record_str = new String(record_block,_encoding); 	
            // substitute styles
            //if self._substyle and self._stylesheet:
            //    record = self._substitute_stylesheet(record);
            show(record_str);       
        	
        	
        }
    }
    
    static volatile int thread_number_count = 1;

    //find in all texts(multithread)
    public void findAllContents_MT(String _key) throws IOException, DataFormatException{
    	
    	final byte[] key = _key.getBytes(_encoding);
        
        fetch_keyBlocksHeaderTextKeyID();
        
        final int thread_number = 5;
        thread_number_count = thread_number;
        final int step = (int) (_num_record_blocks/thread_number);
    	final int yuShu=(int) (_num_record_blocks%thread_number);
        ExecutorService fixedThreadPool = Executors.newFixedThreadPool(thread_number);
        for(int ti=0; ti<thread_number; ti++){//分  thread_number 股线程运行
	    	final int it = ti;
	        fixedThreadPool.execute(
	        new Runnable(){@Override public void run() 
	        {
	            final byte[] record_block_compressed = new byte[(int) maxComRecSize];//!!!避免反复申请内存
	            final byte[] record_block = new byte[(int) maxDecompressedSize];//!!!避免反复申请内存
	            try 
	            {
		            FileInputStream data_in = new FileInputStream(f);
		            data_in.skip(_record_info_struct_list[it*step].compressed_size_accumulator+_record_block_offset+_number_width*4+_num_record_blocks*2*_number_width);
		            int jiaX=0;
		            if(it==thread_number-1) jiaX=yuShu;
	            	for(int i=it*step; i<it*step+step+jiaX; i++)//_num_record_blocks
	            	{
	                    final int RidxI=i;
	                    record_info_struct RinfoI = _record_info_struct_list[i];
	                    
	                    int compressed_size = (int) RinfoI.compressed_size;
	                    int decompressed_size = (int) RinfoI.decompressed_size;
	                    data_in.read(record_block_compressed,0, compressed_size);//,0, compressed_size
	                    byte[] record_block_type = new byte[4];
	                    System.arraycopy(record_block_compressed, 0, record_block_type, 0, 4);
	                    
	                    //解压开始
	                    if(compareByteArrayIsPara(record_block_type,_zero4)){
	                        System.arraycopy(record_block_compressed, 8, record_block, 0, compressed_size-8);
	                    }
	                    else if(compareByteArrayIsPara(record_block_type,_1zero3)){
	                        MInt len = new MInt((int) decompressed_size);
	                        byte[] arraytmp = new byte[ compressed_size];
	                        System.arraycopy(record_block_compressed, 8, arraytmp, 0, (compressed_size-8));
	                        MiniLZO.lzo1x_decompress(arraytmp,(int) compressed_size,record_block,len);
	                    }
	                    else if(compareByteArrayIsPara(record_block_type,_2zero3)){    
	                        Inflater inf = new Inflater();
	                        inf.setInput(record_block_compressed,8,compressed_size-8);
	                        int ret = inf.inflate(record_block,0,decompressed_size);  				
	                    }
	                    //内容块解压完毕
	                    
	                    int idx = indexOf(record_block,0,decompressed_size,key,0,key.length,0);
	                    while(idx!=-1){
	                        long off = RinfoI.decompressed_size_accumulator+idx;
	                        int key_block_id = binary_find_closest(keyBlocksHeaderTextKeyID,off);
	                        prepareItemByKeyInfo(_key_block_info_list[key_block_id],key_block_id);
	                        long[] ko = _key_block_info_list[key_block_id].key_offsets;
	                        int relative_pos = binary_find_closest(ko,off);
	                        int pos = (int) (_key_block_info_list[key_block_id].num_entries_accumulator+relative_pos);
	                        show(getEntryAt(pos));
	                        //show(idx+" "+RidxI);
	                        //byte[] digest = new byte[512];
	                        //System.arraycopy(record_block, idx, digest, 0, digest.length);
	                        //show(new String(digest,_encoding));
	                        int recordodKeyLen = 0;
	                        if(relative_pos<ko.length-1){//不是最后一个entry
	                        	recordodKeyLen=(int) (idx+ko[relative_pos+1]-ko[relative_pos]);
			                    idx = indexOf(record_block,0,decompressed_size,key,0,key.length,idx+recordodKeyLen);
	                        }
	                        else if(key_block_id<keyBlocksHeaderTextKeyID.length-1){//不是最后一块key block
	                        	recordodKeyLen=(int) (keyBlocksHeaderTextKeyID[key_block_id+1]-ko[relative_pos]);
			                    idx = indexOf(record_block,0,decompressed_size,key,0,key.length,idx+recordodKeyLen);
	                        }
                        	else
    		                    idx = -1;
	                    }	    
	                }
	                
	            }catch (FileNotFoundException e) {
	                e.printStackTrace();
	            } catch (DataFormatException e) {
	                e.printStackTrace();
	            } catch (IOException e) {
	                e.printStackTrace();
	            }finally{
	            	thread_number_count--;
	            }
	        }});
        }
        fixedThreadPool.execute(
        new Runnable(){@Override public void run() 
        {
        	while(thread_number_count>0){
        		try {
					Thread.sleep(5);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
        	}
        	show("done! time cosumption is :"+(System.currentTimeMillis()-MdTest.stst));
        }});
        fixedThreadPool.shutdown();
			

    }

    /*
     * https://stackoverflow.com/questions/21341027/find-indexof-a-byte-array-within-another-byte-array
     * Gustavo Mendoza's Answer*/
    static int indexOf(byte[] source, int sourceOffset, int sourceCount, byte[] target, int targetOffset, int targetCount, int fromIndex) {
        if (fromIndex >= sourceCount) {
            return (targetCount == 0 ? sourceCount : -1);
        }
        if (fromIndex < 0) {
            fromIndex = 0;
        }
        if (targetCount == 0) {
            return fromIndex;
        }

        byte first = target[targetOffset];
        int max = sourceOffset + (sourceCount - targetCount);

        for (int i = sourceOffset + fromIndex; i <= max; i++) {
            /* Look for first character. */
            if (source[i] != first) {
                while (++i <= max && source[i] != first)
                    ;
            }

            /* Found first character, now look at the rest of v2 */
            if (i <= max) {
                int j = i + 1;
                int end = j + targetCount - 1;
                for (int k = targetOffset + 1; j < end && source[j] == target[k]; j++, k++)
                    ;

                if (j == end) {
                    /* Found whole string. */
                    return i - sourceOffset;
                }
            }
        }
        return -1;
    }
   
    //for list view
	public String getEntryAt(int position) {
        int blockId = accumulation_blockId_tree.xxing(new mdict.myCpr(position,1)).getKey().value;
        key_info_struct infoI = _key_block_info_list[blockId];
        long start = infoI.key_block_compressed_size_accumulator;
        long compressedSize;
        prepareItemByKeyInfo(infoI,blockId);
        return infoI.keys[(int) (position-infoI.num_entries_accumulator)];
		
	}
	
    //Advanced mdict conjunction search.
	public void size_confined_lookUp(String keyword,
			RBTree_additive combining_search_tree, int SelfAtIdx, int theta) 
			throws UnsupportedEncodingException 
		{
		//Downward_lookUp(key,theta);
    	keyword = keyword.toLowerCase().replaceAll(replaceReg,emptyStr);
        int blockId = block_blockId_search_tree.sxing(new myCpr(keyword,1)).getKey().value;
        //int blockId = binary_find_closest_HTText_blockId_List(keyword);
        //show("blockId:"+blockId);
        int res;
        if(_encoding.equals("GB18030"))
        while(blockId!=0 &&  compareByteArray(_key_block_info_list[blockId-1].tailerKeyText.getBytes(),keyword.getBytes())>=0)
        	blockId--;
        else
        while(blockId!=0 &&  _key_block_info_list[blockId-1].tailerKeyText.compareTo(keyword)>=0)
        	blockId--;
        key_info_struct infoI = _key_block_info_list[blockId];

        prepareItemByKeyInfo(infoI,blockId);
        
        if(_encoding.equals("GB18030")){
            res = binary_find_closest2(infoI.keys,keyword);//keyword
        }else
        	res = binary_find_closest(infoI.keys,keyword);//keyword

        if (res==-1){
        	System.out.println("search failed!"+keyword);
        }
        else{
        	String KeyText= infoI.keys[res];
        	//show("match key "+KeyText+" at "+res);
        	int lvOffset = (int) (infoI.num_entries_accumulator+res);
        	int end = Math.min(res+theta, infoI.keys.length-1);
        	int yuShu = theta-(end-res);
        	//show(" end key "+infoI.keys[end]+" at "+end+"yuShu"+yuShu);
        	int start = res;
        	if(!infoI.keys[end].startsWith(keyword))
        	while(start<end)//进入二分法
        	if(infoI.keys[end].startsWith(keyword)){
        		int dist = end-start+1;
        		start = end;
        		end = end+(dist)/2;//扩大
        	}else{
        		end = (start+end)/2;//缩小
        	}
        	for(int i=res;i<=end;i++){
            	//show("pre match combining_search_tree inserting "+infoI.keys[i]+" at "+i);
        		combining_search_tree.insert(infoI.keys[i],(int)(infoI.num_entries_accumulator+i),SelfAtIdx);
        	}
        	//show("pre match end key "+infoI.keys[end]+" at "+end);
        	while(yuShu>0){//要进入下一个key_block查询
        		if(++blockId>=_key_block_info_list.length) break;
        		start = 0;
                key_info_struct infoIi = _key_block_info_list[blockId];
                prepareItemByKeyInfo(infoIi,blockId);
                if(!infoIi.keys[0].startsWith(keyword)) break;
            	//show("2 start key "+infoIi.keys[start]+" at "+start);
            	end = Math.min(yuShu-1, infoIi.keys.length-1);
            	yuShu = yuShu-infoIi.keys.length;//如果大于零，则仍然需要查询下一个词块
            	//show("2 end key "+infoIi.keys[end]+" at "+end);
            	if(!infoIi.keys[end].startsWith(keyword))
            	while(start<end)//进入二分法
                	if(infoIi.keys[end].startsWith(keyword)){
                		int dist = end-start+1;
                		start = end;
                		end = end+(dist)/2;//扩大
                	}else{
                		end = (start+end)/2;//缩小
                	}
                	//show("2 match end key "+infoIi.keys[end]+" at "+end);
                	for(int i=0;i<=end;i++){
                		combining_search_tree.insert(infoIi.keys[i],(int)(infoIi.num_entries_accumulator+i),SelfAtIdx);
                	}
        	}
        	
        	
        }   
		
	}   

    public int lookUp(String keyword)
                            throws UnsupportedEncodingException
    {
    	keyword = keyword.toLowerCase().replaceAll(replaceReg,emptyStr);
        int blockId = block_blockId_search_tree.sxing(new myCpr(keyword,1)).getKey().value;
        //int blockId = binary_find_closest_HTText_blockId_List(keyword);
        //show("blockId:"+blockId);
        int res;
        if(_encoding.equals("GB18030"))
        while(blockId!=0 &&  compareByteArray(_key_block_info_list[blockId-1].tailerKeyText.getBytes(),keyword.getBytes())>=0)
        	blockId--;
        else
        while(blockId!=0 &&  _key_block_info_list[blockId-1].tailerKeyText.compareTo(keyword)>=0)
        	blockId--;
        key_info_struct infoI = _key_block_info_list[blockId];

        prepareItemByKeyInfo(infoI,blockId);
        
        if(_encoding.equals("GB18030")){
            res = binary_find_closest2(infoI.keys,keyword);//keyword
        }else
        	res = binary_find_closest(infoI.keys,keyword);//keyword

        if (res==-1){
        	System.out.println("search failed!"+keyword);
        	return -1;
        }
        else{
        	String KeyText= infoI.keys[res];
        	//show("match key "+KeyText+" at "+res);
        	long lvOffset = infoI.num_entries_accumulator+res;
        	long wjOffset = infoI.key_block_compressed_size_accumulator+infoI.key_offsets[res];
        	return (int) lvOffset;
        }   
    }


    public int  binary_find_closest_HTText_blockId_List(String val){
    	int middle = 0;
    	int iLen = _HTText_blockId_List.length;
    	int low=0,high=iLen-1;
    	if(_HTText_blockId_List==null || iLen<1){
    		return -1;
    	}
    	if(iLen==1){
    		return 0;
    	}
    	if(val.compareTo(_HTText_blockId_List[0].toLowerCase().replaceAll(replaceReg,emptyStr))<=0){
			return 0;
    	}else if(val.compareTo(_HTText_blockId_List[iLen-1].toLowerCase().replace(" ",emptyStr).replace("-",emptyStr))>=0){
    		return iLen-1;
    	}
		//System.out.println(array[0]+":"+array[array.length-1]);
		//System.out.println(array[0]+":"+val.compareTo(array[0].toLowerCase().replaceAll(replaceReg,emptyStr)));
		//System.out.println(array[0]+":"+val);
		//System.out.println(array[0]+":"+array[0].toLowerCase().replaceAll("[: . , - ]",emptyStr));


    	int counter=0;
    	int subStrLen1,subStrLen0,cprRes1,cprRes0,cprRes;String houXuan1,houXuan0;
    	while(low<high){
    		counter+=1;
    		//System.out.println(low+":"+high);
    		middle = (low+high)/2;
    		houXuan1 = _HTText_blockId_List[middle+1].toLowerCase().replaceAll(replaceReg,emptyStr);
    		houXuan0 = _HTText_blockId_List[middle  ].toLowerCase().replaceAll(replaceReg,emptyStr);
    		cprRes1=houXuan1.compareTo(val);
        	cprRes0=houXuan0.compareTo(val);
        	if(cprRes1>0&&cprRes0>=0){
        		high=middle;
        		//System.out.println(houXuan1);
        		//System.out.println(houXuan0);
        	}else if(cprRes1<=0&&cprRes0<0){
        		//System.out.println("cprRes1<=0 && cprRes0<0");
        		//System.out.println(houXuan1);
        		//System.out.println(houXuan0);
        		low=middle+1;
        	}else if(cprRes1>=0 && cprRes0<0){
        		low=middle+1;
        	}else{
        		high=middle;
        	}
    	}
    	
    	int resPreFinal;
    	if(low==high) resPreFinal = high;
    	else{
    		resPreFinal = Math.abs(_HTText_blockId_List[low].toLowerCase().replaceAll(replaceReg,emptyStr).compareTo(val))>Math.abs(_HTText_blockId_List[high].toLowerCase().replaceAll(replaceReg,emptyStr).compareTo(val))?high:low;
    	}
		//System.out.println(resPreFinal);
		//System.out.println("执行了几次："+counter);
    	houXuan1 = _HTText_blockId_List[resPreFinal].toLowerCase().replaceAll(replaceReg,emptyStr);
    	//show("houXuan1"+houXuan1);
    	if(val.length()>houXuan1.length())
    		return -1;//判为矢匹配.
    	else{
    		if(houXuan1.substring(0,val.length()).compareTo(val)!=0)
    			return -1;//判为矢匹配.
    		else return resPreFinal;//
    	}
    }
 
    
    public static int  binary_find_closest(String[] array,String val){
    	int middle = 0;
    	int iLen = array.length;
    	int low=0,high=iLen-1;
    	if(array==null || iLen<1){
    		return -1;
    	}
    	//if(iLen==1)
    	//	return 0;
    	
    	if(val.compareTo(array[0].toLowerCase().replaceAll(replaceReg,emptyStr))<=0){
    		if(array[0].toLowerCase().replaceAll(replaceReg,emptyStr).startsWith(val))
    			return 0;
    		else
    			return -1;
    	}else if(val.compareTo(array[iLen-1].toLowerCase().replace(" ",emptyStr).replace("-",emptyStr))>=0){
    		return iLen-1;
    	}
		//System.out.println(array[0]+":"+array[array.length-1]);
		//System.out.println(array[0]+":"+val.compareTo(array[0].toLowerCase().replaceAll(replaceReg,emptyStr)));
		//System.out.println(array[0]+":"+val);
		//System.out.println(array[0]+":"+array[0].toLowerCase().replaceAll("[: . , - ]",emptyStr));


    	int counter=0;
    	int subStrLen1,subStrLen0,cprRes1,cprRes0,cprRes;String houXuan1,houXuan0;
    	while(low<high){
    		counter+=1;
    		//System.out.println(low+":"+high);
    		middle = (low+high)/2;
    		houXuan1 = array[middle+1].toLowerCase().replaceAll(replaceReg,emptyStr);
    		houXuan0 = array[middle  ].toLowerCase().replaceAll(replaceReg,emptyStr);
    		cprRes1=houXuan1.compareTo(val);
        	cprRes0=houXuan0.compareTo(val);
        	if(cprRes1>0&&cprRes0>=0){
        		high=middle;
        		//System.out.println(houXuan1);
        		//System.out.println(houXuan0);
        	}else if(cprRes1<=0&&cprRes0<0){
        		//System.out.println("cprRes1<=0 && cprRes0<0");
        		//System.out.println(houXuan1);
        		//System.out.println(houXuan0);
        		low=middle+1;
        	}else if(cprRes1>=0 && cprRes0<0){
        		low=middle+1;
        	}else{
        		high=middle;
        	}
    	}
    	
    	int resPreFinal;
    	if(low==high) resPreFinal = high;
    	else{
    		resPreFinal = Math.abs(array[low].toLowerCase().replaceAll(replaceReg,emptyStr).compareTo(val))>Math.abs(array[high].toLowerCase().replaceAll(replaceReg,emptyStr).compareTo(val))?high:low;
    	}
		//System.out.println(resPreFinal);
		//System.out.println("执行了几次："+counter);
    	houXuan1 = array[resPreFinal].toLowerCase().replaceAll(replaceReg,emptyStr);
    	//show("houXuan1"+houXuan1);
    	if(val.length()>houXuan1.length())
    		return -1;//判为矢匹配.
    	else{
    		if(houXuan1.substring(0,val.length()).compareTo(val)!=0)
    			return -1;//判为矢匹配.
    		else return resPreFinal;//
    	}
    }
    
    //binary_find_closest: with_charset! with_charset! with_charset!
    public int  binary_find_closest2(String[] array,String val) throws UnsupportedEncodingException{
    	int middle = 0;
    	int iLen = array.length;
    	int low=0,high=iLen-1;
    	if(array==null || iLen<1){
    		return -1;
    	}
    	//if(iLen==1)
    	//	return 0;
    	
    	byte[] valBA = val.getBytes(_encoding);
    	
    	if(compareByteArray(valBA,array[0].toLowerCase().replaceAll(replaceReg,emptyStr).getBytes(_encoding))<=0){
    		if(array[0].toLowerCase().replaceAll(replaceReg,emptyStr).startsWith(val))
    			return 0;
    		else
    			return -1;
    	}else if(compareByteArray(valBA,array[iLen-1].toLowerCase().replaceAll(replaceReg,emptyStr).getBytes(_encoding))>=0){
    		//System.out.println("执行了几asdas次：");
    		return -1;
    	}
    	int counter=0;
    	int subStrLen1,subStrLen0,cprRes1,cprRes0,cprRes;
    	byte[] houXuan1BA,houXuan0BA;
    	while(low<high){
    		counter+=1;
    		//System.out.println(low+":"+high);
    		middle = (low+high)/2;
    		houXuan1BA = array[middle+1].toLowerCase().replaceAll(replaceReg,emptyStr).getBytes(_encoding);
    		houXuan0BA = array[middle  ].toLowerCase().replaceAll(replaceReg,emptyStr).getBytes(_encoding);
    		cprRes1=compareByteArray(houXuan1BA,valBA);
        	cprRes0=compareByteArray(houXuan0BA,valBA);
        	if(cprRes1>0&&cprRes0>=0){
        		high=middle;
        	}else if(cprRes1<=0&&cprRes0<0){
        		//System.out.println("cprRes1<=0 && cprRes0<0");
        		//System.out.println(houXuan1BA);
        		//System.out.println(houXuan0BA);
        		low=middle+1;
        	}else if(cprRes1>=0 && cprRes0<0){
        		low=middle+1;
        	}else{
        		high=middle;
        	}
    	}
    	
    	int resPreFinal;
    	if(low==high) resPreFinal = high;
    	else{
    		resPreFinal = Math.abs(compareByteArray(array[low].toLowerCase().replaceAll(replaceReg,emptyStr).getBytes(_encoding),valBA))>Math.abs(compareByteArray(array[high].toLowerCase().replaceAll(replaceReg,emptyStr).getBytes(_encoding),valBA))?high:low;
    	}
		//System.out.println(resPreFinal);
		//System.out.println("执行了几次："+counter);
    	String houXuan1 = array[resPreFinal].toLowerCase().replaceAll(replaceReg,emptyStr);
    	//show("houXuan1"+houXuan1);
    	if(val.length()>houXuan1.length())
    		return -1;//判为矢匹配.
    	else{
    		if(houXuan1.substring(0,val.length()).compareTo(val)!=0)
    			return -1;//判为矢匹配.
    		else return resPreFinal;//
    	}
    }
   
    //per-byte byte array comparing
    private final static int compareByteArray(byte[] A,byte[] B){
    	int la = A.length,lb = B.length;
    	for(int i=0;i<Math.min(la, lb);i++){
    		int cpr = (int)(A[i]&0xff)-(int)(B[i]&0xff);
    		if(cpr==0)
    			continue;
    		return cpr;
    	}
    	if(la==lb)
    		return 0;
    	else return la>lb?1:-1;
    }
    //per-byte byte array comparing
    private final static boolean compareByteArrayIsPara(byte[] A,byte[] B){
    	for(int i=0;i<A.length;i++){
    		if(A[i]!=B[i])
    			return false;
    	}
    	return true;
    }
    private final static int indexOf(byte[] outerArray, byte[] smallerArray,int offset) {
        for(int i = offset; i < outerArray.length - smallerArray.length+1; i++) {
            boolean found = true;
            for(int j = 0; j < smallerArray.length; j++) {
               if (outerArray[i+j] != smallerArray[j]) {
                   found = false;
                   break;
               }
            }
            if (found) return i;
         }
       return -1;  
    }
    public static int  binary_find_closest(long[] array,long val){
    	int middle = 0;
    	int iLen = array.length;
    	int low=0,high=iLen-1;
    	if(array==null || iLen<1){
    		return -1;
    	}
    	if(iLen==1){
    		return 0;
    	}
    	if(val-array[0]<=0){
			return 0;
    	}else if(val-array[iLen-1]>=0){
    		return iLen-1;
    	}
    	int counter=0;
    	long cprRes1,cprRes0;
    	while(low<high){
    		counter+=1;
    		//System.out.println(low+":"+high);
    		middle = (low+high)/2;
    		cprRes1=array[middle+1]-val;
        	cprRes0=array[middle  ]-val;
        	if(cprRes0>=0){
        		high=middle;
        	}else if(cprRes1<=0){
        		//System.out.println("cprRes1<=0 && cprRes0<0");
        		//System.out.println(houXuan1);
        		//System.out.println(houXuan0);
        		low=middle+1;
        	}else{
        		//System.out.println("asd");
        		high=middle;
        	}
    	}
		return low;
    }
    
    String[] _HTText_blockId_List;
    private key_info_struct[] _decode_key_block_info(byte[] key_block_info_compressed) throws UnsupportedEncodingException {
        key_info_struct[] _key_block_info_list = new key_info_struct[(int) _num_key_blocks];
        _HTText_blockId_List = new String[(int) _num_key_blocks];
    	byte[] key_block_info;
    	if(_version >= 2)
        {   //zlib压缩
    		byte[] asd = new byte[]{key_block_info_compressed[0],key_block_info_compressed[1],key_block_info_compressed[2],key_block_info_compressed[3]};
    		assert(new String(asd).equals(new String(new byte[]{2,0,0,0})));
            //处理 Ripe128md 加密的 key_block_info
    		if(_encrypt==2){try{
                key_block_info_compressed = _mdx_decrypt(key_block_info_compressed);
                } catch (IOException e) {e.printStackTrace();}}
			//!!!getInt CAN BE NEGTIVE ,INCONGRUENT to python CODE
    		//!!!MAY HAVE BUG
            int adler32 = getInt(key_block_info_compressed[4],key_block_info_compressed[5],key_block_info_compressed[6],key_block_info_compressed[7]);
            key_block_info = zlib_decompress(key_block_info_compressed,8);
            assert(adler32 == (calcChecksum(key_block_info) ));
        }
        else
            key_block_info = key_block_info_compressed;
    	// decoding……
        ByteBuffer sf = ByteBuffer.wrap(key_block_info);
        byte[] textbuffer = new byte[1];
        String headerKeyText,tailerKeyText;
        long key_block_compressed_size = 0,key_block_decompressed_size = 0;
        long start1,end1,start2,end2;
        int accumulation_ = 0,num_entries=0;//how many entries before one certain block.for construction of a list.
        int byte_width = 2,text_term = 1;//not DECREPTING version1
        if(_version<2)
        {byte_width = 1;text_term = 0;}
        //System.out.println("_version is"+_version+byte_width);
        //遍历blocks
        for(int i=0;i<_key_block_info_list.length;i++){
            // number of entries in current key block

            start1=System.currentTimeMillis(); //获取开始时间  
        	accumulation_blockId_tree.insert(new myCpr<Integer, Integer>(accumulation_,i));
            end1=System.currentTimeMillis(); //获取结束时间
            accumulation_blockId_tree_TIME+=end1-start1;
            if(_version<2)
            _key_block_info_list[i] = new key_info_struct(sf.getInt(),accumulation_);
            else
            _key_block_info_list[i] = new key_info_struct(sf.getLong(),accumulation_);

            key_info_struct infoI = _key_block_info_list[i];
            accumulation_ += infoI.num_entries;
            
            //![0] head word text
            int text_head_size;
            if(_version<2)
            	text_head_size = sf.get();
        	else
        		text_head_size = sf.getChar();
            if(!_encoding.startsWith("UTF-16")){
                textbuffer = new byte[text_head_size];
                sf.get(textbuffer, 0,text_head_size);
                if(_version>=2)
                sf.get();                
            }else{
                textbuffer = new byte[text_head_size*2];
                sf.get(textbuffer, 0, text_head_size*2);
                if(_version>=2)
                sf.get();if(_version>=2)sf.get();                
            }
            
            infoI.headerKeyText = new String(textbuffer,_encoding);
            //show(infoI.headerKeyText);
            
            //![1]  tail word text
            int text_tail_size;
            if(_version<2)
            	text_tail_size = sf.get();
        	else
        		text_tail_size = sf.getChar();
            if(!_encoding.startsWith("UTF-16")){
                textbuffer = new byte[text_tail_size];
                sf.get(textbuffer, 0, text_tail_size);
                if(_version>=2)
                sf.get();         
            }else{
                textbuffer = new byte[text_tail_size*2];
                sf.get(textbuffer, 0, text_tail_size*2);
                if(_version>=2)
                sf.get();if(_version>=2)sf.get();             
            }
            
            infoI.tailerKeyText = new String(textbuffer,_encoding);
            //show(infoI.tailerKeyText);

            infoI.key_block_compressed_size_accumulator = key_block_compressed_size;
            if(_version<2){
            	key_block_compressed_size += sf.getInt();
            	infoI.key_block_decompressed_size = sf.getInt();
            }else{
            	key_block_compressed_size += sf.getLong();
            	infoI.key_block_decompressed_size = sf.getLong();
            }
            start2=System.currentTimeMillis(); //获取开始时间  
            _HTText_blockId_List[i] = infoI.headerKeyText;
            block_blockId_search_tree.insert(new myCpr<String, Integer>(infoI.headerKeyText,i));
            end2=System.currentTimeMillis(); //获取结束时间
            block_blockId_search_tree_TIME+=end2-start2;
        }
        //assert(accumulation_ == self._num_entries)
        return _key_block_info_list;
	}
    

    //解压等utils
    public static byte[] zlib_decompress(byte[] encdata,int offset) {
	    try {
			    ByteArrayOutputStream out = new ByteArrayOutputStream(); 
			    InflaterOutputStream inf = new InflaterOutputStream(out); 
			    inf.write(encdata,offset, encdata.length-offset); 
			    inf.close(); 
			    return out.toByteArray(); 
		    } catch (Exception ex) {
		    	ex.printStackTrace(); 
		    	return "ERR".getBytes(); 
		    }
    }
    public static byte[] zlib_decompressRAW_METHON(byte[] encdata,int offset,int len) {
	    try {
			    Inflater inf = new Inflater();
			    inf.setInput(encdata,offset,encdata.length-8);
			    byte[] res = new byte[len];
			    int ret = inf.inflate(res,0,len);
			    //show("zlib_decompressRAW"+ret);
			    return res;
		    } catch (Exception ex) {
		    	ex.printStackTrace(); 
		    	return "ERR".getBytes(); 
		    }
    }
    public static String zlib_decompress_to_str(byte[] encdata,int offset) {
	    try {
			    ByteArrayOutputStream out = new ByteArrayOutputStream(); 
			    InflaterOutputStream inf = new InflaterOutputStream(out); 
			    inf.write(encdata,offset, encdata.length-offset); 
			    inf.close(); 
			    return out.toString("ASCII");
		    } catch (Exception ex) {
		    	ex.printStackTrace(); 
		    	return "ERR"; 
		    }
    }    
    
    public static byte[] zlib_decompress(byte[] encdata,int offset,int size) {
	    try {
			    ByteArrayOutputStream out = new ByteArrayOutputStream(); 
			    InflaterOutputStream inf = new InflaterOutputStream(out); 
			    inf.write(encdata,offset, size); 
			    inf.close(); 
			    return out.toByteArray(); 
		    } catch (Exception ex) {
		    	ex.printStackTrace(); 
		    	show(emptyStr);
		    	return "ERR".getBytes(); 
		    }
    }    
 
  
    static void show(String val){System.out.println(val);}

	private long _read_number(ByteBuffer sf) {
    	if(_number_width==4)
    		return sf.getInt();
    	else
    		return sf.getLong();
	}
	private long _read_number(DataInputStream  sf) {
    	if(_number_width==4)
			try {
				return sf.readInt();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				return 0;
			}
		else
			try {
				return sf.readLong();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				return 0;
			}
	}

	private static int calcChecksum(byte[] bytes) {
        Adler32 a32 = new Adler32();
        a32.update(bytes);
        int sum = (int) a32.getValue();
        return sum;
    }
    
	public static short getShort(byte buf1, byte buf2) 
    {
        short r = 0;
        r |= (buf1 & 0x00ff);
        r <<= 8;
        r |= (buf2 & 0x00ff);
        return r;
    }
    
    public static int getInt(byte buf1, byte buf2, byte buf3, byte buf4) 
    {
        int r = 0;
        r |= (buf1 & 0x000000ff);
        r <<= 8;
        r |= (buf2 & 0x000000ff);
        r <<= 8;
        r |= (buf3 & 0x000000ff);
        r <<= 8;
        r |= (buf4 & 0x000000ff);
        return r;
    }
    public static long getLong(byte[] buf) 
    {
        long r = 0;
        r |= (buf[0] & 0xff);
        r <<= 8;
        r |= (buf[1] & 0xff);
        r <<= 8;
        r |= (buf[2] & 0xff);
        r <<= 8;
        r |= (buf[3] & 0xff);
        r <<= 8;
        r |= (buf[4] & 0xff);
        r <<= 8;
        r |= (buf[5] & 0xff);
        r <<= 8;
        r |= (buf[6] & 0xff);
        r <<= 8;
        r |= (buf[7] & 0xff);
        return r;
    }
    public static long getLong(byte buf1, byte buf2, byte buf3, byte buf4,byte buf11, byte buf21, byte buf31, byte buf41) 
    {
        long r = 0;
        r |= (buf1 & 0x000000ff);
        r <<= 8;
        r |= (buf2 & 0x000000ff);
        r <<= 8;
        r |= (buf3 & 0x000000ff);
        r <<= 8;
        r |= (buf4 & 0x000000ff);
        r <<= 8;
        r |= (buf11 & 0x000000ff);
        r <<= 8;
        r |= (buf21 & 0x000000ff);
        r <<= 8;
        r |= (buf31 & 0x000000ff);
        r <<= 8;
        r |= (buf41 & 0x000000ff);
        return r;
    }
    public static String byteTo16(byte bt){
        String[] strHex={"0","1","2","3","4","5","6","7","8","9","a","b","c","d","e","f"};
        String resStr=emptyStr;
        int low =(bt & 15);
        int high = bt>>4 & 15;
        resStr = strHex[high]+strHex[low];
        return resStr;
    }
    
    public void printDictInfo(){
    	show("\r\n开始printDictInfo...");
        Iterator iter = _header_tag.entrySet().iterator();  
        while (iter.hasNext()) {  
            Map.Entry entry = (Map.Entry) iter.next();  
            Object key = entry.getKey();  
            Object value = entry.getValue();  
            System.out.println(key + ":" + value);  
        }  
        show("_num_entries: "+this._num_entries);
        show("_num_key_blocks: "+this._num_key_blocks);
        show("_num_record_blocks: "+this._num_record_blocks);
        show("maxComRecSize: "+this.maxComRecSize);
        show("maxDecompressedSize: "+this.maxDecompressedSize);
        show("\r\n");
    }

 
    
}


