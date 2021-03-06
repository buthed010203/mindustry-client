package mindustry.input;

import arc.*;
import arc.Graphics.*;
import arc.Graphics.Cursor.*;
//import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.input.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.scene.utils.*;
import arc.struct.*;
import arc.util.ArcAnnotate.*;
import arc.util.*;
import mindustry.*;
import mindustry.ai.pathfinding.*;
import mindustry.core.*;
import mindustry.core.GameState.*;
import mindustry.entities.*;
import mindustry.entities.traits.BuilderTrait.*;
import mindustry.entities.type.*;
import mindustry.game.EventType.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.gen.Icon;
import mindustry.graphics.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;
import mindustry.ui.fragments.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.defense.turrets.Turret.*;
import mindustry.world.meta.*;

import javax.swing.*;
import java.security.*;
import java.time.*;
import java.util.*;

import static arc.Core.*;
import static mindustry.Vars.*;
import static mindustry.input.PlaceMode.*;

public class DesktopInput extends InputHandler{
    /** Current cursor type. */
    private Cursor cursorType = SystemCursor.arrow;
    /** Position where the player started dragging a line. */
    private int selectX, selectY, schemX, schemY;
    /** Last known line positions. */
    private int lastLineX, lastLineY, schematicX, schematicY;
    /** Whether selecting mode is active. */
    private PlaceMode mode;
    /** Animation scale for line. */
    private float selectScale;
    /** Selected build request for movement. */
    private @Nullable
    BuildRequest sreq;
    /** Whether player is currently deleting removal requests. */
    private boolean deleting = false;

    private boolean transferPaused = false;

    @Override
    public void buildUI(Group group){
        group.fill(t -> {
            t.bottom().update(() -> t.getColor().a = Mathf.lerpDelta(t.getColor().a, player.isBuilding() ? 1f : 0f, 0.15f));
            t.visible(() -> Core.settings.getBool("hints") && selectRequests.isEmpty());
            t.touchable(() -> t.getColor().a < 0.1f ? Touchable.disabled : Touchable.childrenOnly);
            t.table(Styles.black6, b -> {
                b.defaults().left();
                b.label(() -> Core.bundle.format(!player.isBuilding ? "resumebuilding" : "pausebuilding", Core.keybinds.get(Binding.pause_building).key.toString())).style(Styles.outlineLabel);
                b.row();
                b.label(() -> Core.bundle.format("cancelbuilding", Core.keybinds.get(Binding.clear_building).key.toString())).style(Styles.outlineLabel);
                b.row();
                b.label(() -> Core.bundle.format("selectschematic", Core.keybinds.get(Binding.schematic_select).key.toString())).style(Styles.outlineLabel);
            }).margin(10f);
        });

        group.fill(t -> {
            t.visible(() -> lastSchematic != null && !selectRequests.isEmpty());
            t.bottom();
            t.table(Styles.black6, b -> {
                b.defaults().left();
                b.label(() -> Core.bundle.format("schematic.flip",
                Core.keybinds.get(Binding.schematic_flip_x).key.toString(),
                Core.keybinds.get(Binding.schematic_flip_y).key.toString())).style(Styles.outlineLabel);
                b.row();
                b.table(a -> {
                    a.addImageTextButton("$schematic.add", Icon.save, this::showSchematicSave).colspan(2).size(250f, 50f).disabled(f -> lastSchematic == null || lastSchematic.file != null);
                });
            }).margin(6f);
        });
    }

    @Override
    public void drawTop(){
        Lines.stroke(1f);
        int cursorX = tileX(Core.input.mouseX());
        int cursorY = tileY(Core.input.mouseY());

        //draw selection(s)
        if(mode == placing && block != null){
            for(int i = 0; i < lineRequests.size; i++){
                BuildRequest req = lineRequests.get(i);
                if(i == lineRequests.size - 1 && req.block.rotate){
                    drawArrow(block, req.x, req.y, req.rotation);
                }
                drawRequest(lineRequests.get(i));
            }
        }else if(mode == breaking){
            drawBreakSelection(selectX, selectY, cursorX, cursorY);
        }else if(isPlacing()){
            if(block.rotate){
                drawArrow(block, cursorX, cursorY, rotation);
            }
            Draw.color();
            drawRequest(cursorX, cursorY, block, rotation);
            block.drawPlace(cursorX, cursorY, rotation, validPlace(cursorX, cursorY, block, rotation));
        }

        if(mode == none && !isPlacing()){
            BuildRequest req = getRequest(cursorX, cursorY);
            if(req != null){
                drawSelected(req.x, req.y, req.breaking ? req.tile().block() : req.block, Pal.accent);
            }
        }

        //draw schematic requests
        for(BuildRequest request : selectRequests){
            request.animScale = 1f;
            drawRequest(request);
        }

        if(sreq != null){
            boolean valid = validPlace(sreq.x, sreq.y, sreq.block, sreq.rotation, sreq);
            if(sreq.block.rotate){
                drawArrow(sreq.block, sreq.x, sreq.y, sreq.rotation, valid);
            }

            sreq.block.drawRequest(sreq, allRequests(), valid);

            drawSelected(sreq.x, sreq.y, sreq.block, getRequest(sreq.x, sreq.y, sreq.block.size, sreq) != null ? Pal.remove : Pal.accent);
        }

        if(Core.input.keyDown(Binding.schematic_select) && !Core.scene.hasKeyboard()){
            drawSelection(schemX, schemY, cursorX, cursorY, Vars.maxSchematicSize);
        }

        Draw.reset();
    }

    void checkTargets(float x, float y){
        Unit unit = Units.closestEnemy(player.getTeam(), x, y, 20f, u -> !u.isDead());

        if(unit != null){
            player.setMineTile(null);
            player.target = unit;
        }else{
            Tile tile = world.ltileWorld(x, y);

            if(tile != null && tile.synthetic() && player.getTeam().isEnemy(tile.getTeam())){
                TileEntity entity = tile.entity;
                player.setMineTile(null);
                player.target = entity;
            }else if(tile != null && player.mech.canHeal && tile.entity != null && tile.getTeam() == player.getTeam() && tile.entity.damaged()){
                player.setMineTile(null);
                player.target = tile.entity;
            }
        }
    }

    @Override
    public void update(){
        if(Vars.net.active() && Core.input.keyTap(Binding.player_list) && !ui.chatfrag.autocomplete.isVisible()){
            ui.listfrag.toggle();
        }
        if(!transferPaused){
            boolean updateTransfer = new Rand().chance(1 / 60f);
            for(TransferItem transfer : ui.transfer.transferRequests){
                transfer.run();
                if(updateTransfer){
                    transfer.update();
                }
            }
        }

        for(int i = 0; i < 50; i += 1){
            if(configRequests.size > 0){
                configRequests.removeFirst().runRequest();
                if(configRequests.size % 10 == 0){
                    System.out.println(String.format("%s left...", configRequests.size));
                }
                if(configRequests.size == 0){
                    System.out.println("Done!!");
                }
            }
        }
        if(following != null && following != player){
            float dx = player.x - following.x;
            float dy = player.y - following.y;
            player.moveBy(Mathf.clamp(-dx, -player.mech.maxSpeed, player.mech.maxSpeed),
            Mathf.clamp(-dy, -player.mech.maxSpeed, player.mech.maxSpeed));
            player.isShooting = following.isShooting;
            player.rotation = following.rotation;
            if(player.buildQueue() != following.buildQueue()){
                player.buildQueue().clear();
                for(BuildRequest b : following.buildQueue()){
                    if(breakingFollowing){
                        b.breaking = !b.breaking;
                    }
                    player.buildQueue().addLast(b);
                }
            }
        }
//        Core.camera.position.x = stalking.x;
//        Core.camera.position.y = stalking.y;

        if(((player.getClosestCore() == null && player.isDead()) || state.isPaused()) && !ui.chatfrag.shown()){
            //move camera around
            float camSpeed = !Core.input.keyDown(Binding.dash) ? 3f : 8f;
            Core.camera.position.add(Tmp.v1.setZero().add(Core.input.axis(Binding.move_x), Core.input.axis(Binding.move_y)).nor().scl(Time.delta() * camSpeed));

            if(Core.input.keyDown(Binding.mouse_move)){
                Core.camera.position.x += Mathf.clamp((Core.input.mouseX() - Core.graphics.getWidth() / 2f) * 0.005f, -1, 1) * camSpeed;
                Core.camera.position.y += Mathf.clamp((Core.input.mouseY() - Core.graphics.getHeight() / 2f) * 0.005f, -1, 1) * camSpeed;
            }
        }

        if(Core.input.keyRelease(Binding.select)){
            player.isShooting = false;
        }

        if(!state.is(State.menu) && Core.input.keyTap(Binding.minimap) && !scene.hasDialog() && !(scene.getKeyboardFocus() instanceof TextField)){
            ui.minimapfrag.toggle();
        }

        if(state.is(State.menu) || Core.scene.hasDialog()) return;

        //zoom camera
        if((!Core.scene.hasScroll() || Core.input.keyDown(Binding.diagonal_placement)) && !ui.chatfrag.shown() && Math.abs(Core.input.axisTap(Binding.zoom)) > 0 && !Core.input.keyDown(Binding.rotateplaced) && (Core.input.keyDown(Binding.diagonal_placement) || ((!isPlacing() || !block.rotate) && selectRequests.isEmpty()))){
            renderer.scaleCamera(Core.input.axisTap(Binding.zoom));
        }

        if(player.isDead()){
            cursorType = SystemCursor.arrow;
            return;
        }

        pollInput();
        if(scene.getKeyboardFocus() == null){
            if(input.keyDown(KeyCode.CONTROL_LEFT) && input.keyRelease(KeyCode.F)){
                FloatingDialog dialog = new FloatingDialog("find");
                dialog.addCloseButton();
                Array<Image> imgs = new Array<>();
                for(int i = 0; i < 10; i += 1){
                    imgs.add(new Image());
                }
                TextField field = Elements.newField("", (string) -> {
                    Array<Block> sorted = content.blocks().copy();
                    sorted = sorted.sort((b) -> distance(string, b.name));
                    found = sorted.first();
                    for(int i = 0; i < imgs.size - 1; i += 1){
                        Image region = new Image(sorted.get(i).icon(Cicon.large));
                        region.setSize(32);
                        imgs.get(i).setDrawable(region.getDrawable());
                    }

                });
                dialog.cont.add(field);
                for(Image img : imgs){
                    dialog.cont.row().add(img);
                }

                dialog.keyDown(KeyCode.ENTER, () -> {
                    if(found == null){
                        dialog.hide();
                    }
                    Array<Tile> tiles = new Array<>();
                    for(Tile[] t : world.getTiles()){
                        for(Tile tile2 : t){
                            if(tile2.block() != null){
                                if(tile2.block().name.equals(found.name) && tile2.getTeam() == player.getTeam()){
                                    tiles.add(tile2);
                                }
                            }
                        }
                    }
                    if(tiles.size > 0){
                        float dist = Float.POSITIVE_INFINITY;
                        Tile closest = null;

                        for(Tile t : tiles){
                            float d = Mathf.dst(player.x, player.y, t.x, t.y);
                            if(d < dist){
                                closest = t;
                                dist = d;

                            }
                        }
                        if(closest != null){
                            targetPosition.set(closest.x, closest.y);
                            ui.chatfrag.addMessage(String.format("%d, %d (!go to travel there)", (int)closest.x, (int)closest.y), "client");
                            dialog.hide();
                        }
                    }
                });
                dialog.show();
                findField = dialog;
                scene.setKeyboardFocus(field);
            }

            if(Core.input.keyDown(KeyCode.CONTROL_LEFT) &&
            Core.input.keyRelease(KeyCode.Z)){
                if(player.log.size > 0){
                    BuildRequest req = player.log.pop().undoRequest();
                    if(req != null){
                        player.buildQueue().addLast(req);
                    }
                }
            }

            if(Core.input.keyTap(KeyCode.R)){
                cameraPositionOverride = null;
            }

            if(Core.input.keyTap(KeyCode.N)){
                if(cameraPositionOverride != null){
                    followingWaypoints = true;
                    repeatWaypoints = false;
                    notDone.addFirst(new Waypoint(cameraPositionOverride.x, cameraPositionOverride.y));
                }
            }

            if(Core.input.keyTap(KeyCode.Z)){
                if(cameraPositionOverride != null){
                    player.navigateTo(camera.position.x, camera.position.y);
                }
            }

            if(Core.input.keyTap(KeyCode.B)){
                autoBuild = !autoBuild;
            }

            if(input.keyTap(KeyCode.SEMICOLON)){
                autoMine = !autoMine;
            }
            if(autoMine){
                player.setState(player.mine);
            }else{
                player.setState(player.normal);
            }
        }

        float speed = (8F / renderer.getScale()) * Time.delta();
        if(scene.getKeyboardFocus() == null){
            if(Core.input.keyDown(KeyCode.LEFT) || Core.input.keyDown(KeyCode.RIGHT) ||
            Core.input.keyDown(KeyCode.UP) || Core.input.keyDown(KeyCode.DOWN)){
                if(cameraPositionOverride == null){
                    cameraPositionOverride = new Vec2(player.x, player.y);
                }
            }

            if(Core.input.keyDown(KeyCode.RIGHT)){
                cameraPositionOverride.x += speed;
            }

            if(Core.input.keyDown(KeyCode.LEFT)){
                cameraPositionOverride.x -= speed;
            }

            if(Core.input.keyDown(KeyCode.UP)){
                cameraPositionOverride.y += speed;
            }

            if(Core.input.keyDown(KeyCode.DOWN)){
                cameraPositionOverride.y -= speed;
            }
        }

        //deselect if not placing
        if(!isPlacing() && mode == placing){
            mode = none;
        }

        if(player.isShooting && !canShoot()){
            player.isShooting = false;
        }

        if(isPlacing()){
            cursorType = SystemCursor.hand;
            selectScale = Mathf.lerpDelta(selectScale, 1f, 0.2f);
        }else{
            selectScale = 0f;
        }

        if(!Core.input.keyDown(Binding.diagonal_placement) && Math.abs((int)Core.input.axisTap(Binding.rotate)) > 0){
            rotation = Mathf.mod(rotation + (int)Core.input.axisTap(Binding.rotate), 4);

            if(sreq != null){
                sreq.rotation = Mathf.mod(sreq.rotation + (int)Core.input.axisTap(Binding.rotate), 4);
            }

            if(isPlacing() && mode == placing){
                updateLine(selectX, selectY);
            }else if(!selectRequests.isEmpty()){
                rotateRequests(selectRequests, (int)Core.input.axisTap(Binding.rotate));
            }
        }

        Tile cursor = tileAt(Core.input.mouseX(), Core.input.mouseY());

        if(cursor != null){
            cursor = cursor.link();

            cursorType = cursor.block().getCursor(cursor);

            if(isPlacing() || !selectRequests.isEmpty()){
                cursorType = SystemCursor.hand;
            }

            if(!isPlacing() && canMine(cursor)){
                cursorType = ui.drillCursor;
            }

            if(getRequest(cursor.x, cursor.y) != null && mode == none){
                cursorType = SystemCursor.hand;
            }

            if(canTapPlayer(Core.input.mouseWorld().x, Core.input.mouseWorld().y)){
                cursorType = ui.unloadCursor;
            }

            if(cursor.interactable(player.getTeam()) && !isPlacing() && Math.abs(Core.input.axisTap(Binding.rotate)) > 0 && Core.input.keyDown(Binding.rotateplaced) && cursor.block().rotate){
                Call.rotateBlock(player, cursor, Core.input.axisTap(Binding.rotate) > 0);
            }
        }

        if(!Core.scene.hasMouse()){
            Core.graphics.cursor(cursorType);
        }

        cursorType = SystemCursor.arrow;
    }

    public float distance(String word, String word2){
        if(word2.toLowerCase().contains(word)){
            if(word2.toLowerCase().equals(word.toLowerCase())){
                return 0F;
            }
            if(word2.toLowerCase().startsWith(word)){
                // Discount for if the word starts with the input
                return 0.25F * Levenshtein.distance(word.toLowerCase(), word2.toLowerCase());
            }else{
                // Discount for if the word contains the input
                return 0.5F * Levenshtein.distance(word.toLowerCase(), word2.toLowerCase());
            }
        }
        return Levenshtein.distance(word, word2);
    }

    @Override
    public void useSchematic(Schematic schem){
        block = null;
        schematicX = tileX(getMouseX());
        schematicY = tileY(getMouseY());

        selectRequests.clear();
        selectRequests.addAll(schematics.toRequests(schem, schematicX, schematicY, Core.input.shift()));
        mode = none;
    }

    @Override
    public boolean isBreaking(){
        return mode == breaking;
    }

    @Override
    public void buildPlacementUI(Table table){
        table.addImage().color(Pal.gray).height(4f).colspan(4).growX();
        table.row();
        table.left().margin(0f).defaults().size(48f).left();

        table.addImageButton(Icon.paste, Styles.clearPartiali, ui.schematics::show);

        ImageButton button = new ImageButton(Icon.redo, Styles.clearPartiali);
//        button.setStyle(Styles.clearPartiali);
        button.clicked(ui.transfer::show);
        TextTooltip.addTooltip(button, "Open item transfer dialog");
        table.add(button);

        ImageButton button1 = new ImageButton(Icon.pause);
        button1.replaceImage(new Image(Icon.pause));
        button1.setStyle(Styles.clearPartiali);
        button1.clicked(() -> {
            transferPaused = !transferPaused;
            if(transferPaused){
                button1.replaceImage(new Image(Icon.play));
            }else{
                button1.replaceImage(new Image(Icon.pause));
            }
        });
        TextTooltip.addTooltip(button1, "Pause item transfer");
        table.add(button1);
    }

    void pollInput(){
        if(scene.getKeyboardFocus() instanceof TextField) return;

        Tile selected = tileAt(Core.input.mouseX(), Core.input.mouseY());
        int cursorX = tileX(Core.input.mouseX());
        int cursorY = tileY(Core.input.mouseY());
        int rawCursorX = world.toTile(Core.input.mouseWorld().x), rawCursorY = world.toTile(Core.input.mouseWorld().y);

        // automatically pause building if the current build queue is empty
        if(Core.settings.getBool("buildautopause") && player.isBuilding && !player.isBuilding()){
            player.isBuilding = false;
            player.buildWasAutoPaused = true;
        }

        if(!selectRequests.isEmpty()){
            int shiftX = rawCursorX - schematicX, shiftY = rawCursorY - schematicY;

            selectRequests.each(s -> {
                s.x += shiftX;
                s.y += shiftY;
            });

            schematicX += shiftX;
            schematicY += shiftY;
        }

        if(Core.input.keyTap(Binding.deselect)){
            player.setMineTile(null);
        }

        if(Core.input.keyTap(Binding.clear_building)){
            player.clearBuilding();
        }

        if(Core.input.keyTap(Binding.schematic_select) && !Core.scene.hasKeyboard()){
            schemX = rawCursorX;
            schemY = rawCursorY;
        }

        if(Core.input.keyTap(Binding.schematic_menu) && !Core.scene.hasKeyboard()){
            if(ui.schematics.isShown()){
                ui.schematics.hide();
            }else{
                ui.schematics.show();
            }
        }

        if(Core.input.keyTap(Binding.clear_building) || isPlacing()){
            lastSchematic = null;
            selectRequests.clear();
        }

        if(Core.input.keyRelease(Binding.schematic_select) && !Core.scene.hasKeyboard()){
            lastSchematic = schematics.create(schemX, schemY, rawCursorX, rawCursorY);
            useSchematic(lastSchematic);
            if(selectRequests.isEmpty()){
                lastSchematic = null;
            }
        }

        if(!selectRequests.isEmpty()){
            if(Core.input.keyTap(Binding.schematic_flip_x)){
                flipRequests(selectRequests, true);
            }

            if(Core.input.keyTap(Binding.schematic_flip_y)){
                flipRequests(selectRequests, false);
            }
        }

        if(sreq != null){
            float offset = ((sreq.block.size + 2) % 2) * tilesize / 2f;
            float x = Core.input.mouseWorld().x + offset;
            float y = Core.input.mouseWorld().y + offset;
            sreq.x = (int)(x / tilesize);
            sreq.y = (int)(y / tilesize);
        }

        if(block == null || mode != placing){
            lineRequests.clear();
        }

        if(Core.input.keyTap(Binding.pause_building)){
            player.isBuilding = !player.isBuilding;
            player.buildWasAutoPaused = false;
        }

        if((cursorX != lastLineX || cursorY != lastLineY) && isPlacing() && mode == placing){
            updateLine(selectX, selectY);
            lastLineX = cursorX;
            lastLineY = cursorY;
        }

        if(Core.input.keyTap(Binding.select) && !Core.scene.hasMouse()){
            BuildRequest req = getRequest(cursorX, cursorY);

            if(Core.input.keyDown(Binding.break_block)){
                mode = none;
            }else if(!selectRequests.isEmpty()){
                flushRequests(selectRequests);
            }else if(isPlacing()){
                selectX = cursorX;
                selectY = cursorY;
                lastLineX = cursorX;
                lastLineY = cursorY;
                mode = placing;
                updateLine(selectX, selectY);
            }else if(req != null && !req.breaking && mode == none && !req.initialized){
                sreq = req;
            }else if(req != null && req.breaking){
                deleting = true;
            }else if(selected != null){
                if(Core.input.keyDown(KeyCode.CONTROL_LEFT) || Core.input.keyDown(KeyCode.CONTROL_RIGHT)){
                    StringBuilder builder = new StringBuilder();
                    for(TileLogItem item : selected.log){
                        builder.append(item.toString()).append("\n");
                    }
                    ui.chatfrag.addMessage(builder.toString(), "client");
                }else{
                    //only begin shooting if there's no cursor event
                    if(!tileTapped(selected) && !tryTapPlayer(Core.input.mouseWorld().x, Core.input.mouseWorld().y) && (player.buildQueue().size == 0 || !player.isBuilding) && !droppingItem &&
                    !tryBeginMine(selected) && player.getMineTile() == null && !Core.scene.hasKeyboard()){
                        player.isShooting = true;
                        if(following != null && following != player){
                            player.isShooting = following.isShooting;
                        }
                    }
                }
            }else if(!Core.scene.hasKeyboard()){ //if it's out of bounds, shooting is just fine
                player.isShooting = true;
                if(following != null && following != player){
                    player.isShooting = following.isShooting;
                }
            }
        }else if(Core.input.keyTap(Binding.deselect) && isPlacing()){
            block = null;
            mode = none;
        }else if(Core.input.keyTap(Binding.deselect) && !selectRequests.isEmpty()){
            selectRequests.clear();
            lastSchematic = null;
        }else if(Core.input.keyTap(Binding.break_block) && !Core.scene.hasMouse()){
            //is recalculated because setting the mode to breaking removes potential multiblock cursor offset
            deleting = false;
            mode = breaking;
            selectX = tileX(Core.input.mouseX());
            selectY = tileY(Core.input.mouseY());
        }

        if(Core.input.keyDown(Binding.select) && mode == none && !isPlacing() && deleting){
            BuildRequest req = getRequest(cursorX, cursorY);
            if(req != null && req.breaking){
                player.buildQueue().remove(req);
            }
        }else{
            deleting = false;
        }
        if(following != null && following != player){
            player.isShooting = following.isShooting;
        }

        if(mode == placing && block != null){
            if(!overrideLineRotation && !Core.input.keyDown(Binding.diagonal_placement) && (selectX != cursorX || selectY != cursorY) && ((int)Core.input.axisTap(Binding.rotate) != 0)){
                rotation = ((int)((Angles.angle(selectX, selectY, cursorX, cursorY) + 45) / 90f)) % 4;
                overrideLineRotation = true;
            }
        }else{
            overrideLineRotation = false;
        }

        if(Core.input.keyRelease(Binding.break_block) || Core.input.keyRelease(Binding.select)){

            if(mode == placing && block != null){ //touch up while placing, place everything in selection
                flushRequests(lineRequests);
                lineRequests.clear();
                Events.fire(new LineConfirmEvent());
            }else if(mode == breaking){ //touch up while breaking, break everything in selection
                removeSelection(selectX, selectY, cursorX, cursorY);
            }

            if(selected != null){
                tryDropItems(selected.link(), Core.input.mouseWorld().x, Core.input.mouseWorld().y);
            }

            if(sreq != null){
                if(getRequest(sreq.x, sreq.y, sreq.block.size, sreq) != null){
                    player.buildQueue().remove(sreq, true);
                }
                sreq = null;
            }

            mode = none;
        }

        if(Core.input.keyTap(Binding.toggle_power_lines)){
            if(Core.settings.getInt("lasersopacity") == 0){
                Core.settings.put("lasersopacity", Core.settings.getInt("preferredlaseropacity", 100));
            }else{
                Core.settings.put("preferredlaseropacity", Core.settings.getInt("lasersopacity"));
                Core.settings.put("lasersopacity", 0);
            }
        }
    }

    @Override
    public boolean selectedBlock(){
        return isPlacing() && mode != breaking;
    }

    @Override
    public float getMouseX(){
        return Core.input.mouseX();
    }

    @Override
    public float getMouseY(){
        return Core.input.mouseY();
    }

    @Override
    public void updateState(){
        if(state.is(State.menu)){
            droppingItem = false;
            mode = none;
            block = null;
            sreq = null;
            selectRequests.clear();
        }
    }
}
