package jp.rai42.umetate;
import java.util.BitSet;
/** Non-air occupancy, independent of solid fill accounting. No world reads on a cast. */
final class ColumnHeights {
    private final Box box;
    private final BitSet[] columns;
    ColumnHeights(Box box) {this.box=box;columns=new BitSet[box.width()*box.width()];for(int i=0;i<columns.length;i++)columns[i]=new BitSet(box.height());}
    void update(int index,boolean nonAir) {columns[index%columns.length].set(index/columns.length,nonAir);}
    boolean nonAir(int index){return columns[index%columns.length].get(index/columns.length);}
    int highest() {int h=0;for(BitSet c:columns)h=Math.max(h,c.length());return box.y()+h;}
    double spawnY(Double lidTop,int worldMax) {return Math.min((lidTop==null?highest():lidTop)+3,worldMax-1);}
}
