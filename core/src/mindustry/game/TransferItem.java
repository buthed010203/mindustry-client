package mindustry.game;

import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import mindustry.type.*;

import javax.print.attribute.standard.*;

public class TransferItem{
    public Item item;
    public TransferEndpoint start;
    public TransferEndpoint end;
    public boolean paused = false;

    public TransferItem(TransferEndpoint startPoint, TransferEndpoint endpoint, Item item){
        start = startPoint;
        end = endpoint;
        this.item = item;
    }

    public void run(){
        if(paused){
            return;
        }
        try{
            start.transferToPlayer(item);
            end.transferFromPlayer(item);
            start.transferFromPlayer(item);
        }catch(NullPointerException ignored){} //SHH EVERYTHING'S FINE
    }

    public void update(){
        start.rebuild();
        end.rebuild();
    }

    public Table show(){
        Table table = new Table();
        table.add(start.toElement());
        table.add(new Label(" "));
        table.add(end.toElement());
        return table;
    }
}
