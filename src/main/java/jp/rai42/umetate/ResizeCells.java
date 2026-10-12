package jp.rai42.umetate;
import java.util.*;
/** Lazy affected-region traversal. Shared unchanged interior is never materialized as Cell objects. */
final class ResizeCells implements Iterable<Box.Cell> {
    final Box before,after;
    ResizeCells(Box before,Box after){this.before=before;this.after=after;}
    boolean contains(int x,int y,int z){
        boolean old=before.envelope(x,y,z),next=after.envelope(x,y,z);
        return old!=next || before.shell(x,y,z) || after.shell(x,y,z) || (before.height()!=after.height() && next);
    }
    public Iterator<Box.Cell> iterator(){
        int r=Math.max(before.radius(),after.radius()),h=Math.max(before.height(),after.height()),w=r*2+1,total=w*w*(h+1);
        return new Iterator<>() {
            int cursor;Box.Cell next;
            public boolean hasNext(){
                while(next==null && cursor<total){int i=cursor++,x=before.x()-r+i%w,y=before.y()+i/(w*w),z=before.z()-r+i/w%w;if(contains(x,y,z))next=new Box.Cell(x,y,z);}
                return next!=null;
            }
            public Box.Cell next(){if(!hasNext())throw new NoSuchElementException();var value=next;next=null;return value;}
        };
    }
}
