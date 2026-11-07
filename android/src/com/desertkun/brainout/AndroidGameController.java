package com.desertkun.brainout;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.desertkun.brainout.controllers.GameController;
import com.desertkun.brainout.events.GameControllerEvent;
import com.desertkun.brainout.events.SelectPreviousSlotEvent;
import com.desertkun.brainout.events.SelectSlotEvent;

/**
 * Touch controls for a match.
 *
 * Two floating sticks driven by raw multi-touch pointers: a touch in the left
 * half of the screen drives the move stick, a touch in the right half drives the
 * aim stick (which also auto-fires past a threshold). Buttons live on a
 * {@link Stage} and get first refusal on every touch, so move, aim+fire and a
 * button can all be held at once:
 *
 *   bottom right  weapon slots 1-4 + last, weapon / reload / crouch / use
 *   top left      menu (pause), players, chat, zoom, more
 *   "more"        drop weapon, drop ammo, unload, fire mode, change team
 *
 * The Android back button opens the pause menu in a match, like Escape on a
 * desktop. Active only in the {@code action} / {@code actionWithNoMouseLocking}
 * modes.
 *
 * Everything here is in real screen pixels (touch coordinates and this stage's
 * ScreenViewport), not the scaled UI units of BrainOutClient.getWidth/getHeight.
 */
public class AndroidGameController extends GameController
{
    private static final int NONE = -1;

    private Stage ui;
    private Image moveBase, moveKnob, aimBase, aimKnob;
    private Table cluster, utility, morePanel;

    private int movePointer = NONE;
    private int aimPointer = NONE;

    private final Vector2 moveOrigin = new Vector2();
    private final Vector2 moveCur = new Vector2();
    private final Vector2 aimOrigin = new Vector2();
    private final Vector2 aimCur = new Vector2();

    private final Vector2 moveVec = new Vector2();   // current move direction sent
    private final Vector2 aimMouse = new Vector2();  // scratch for absolute aim
    private final Vector2 stageTmp = new Vector2();

    private boolean firing;
    private boolean running;
    private boolean crouching;

    private int lastWidth = -1, lastHeight = -1;

    @Override
    public void init()
    {
        // The HUD is built lazily (ensureUi) the first time the combat mode
        // activates: the skin drawables it needs (touchpad-*, button styles) are
        // only loaded with the mainmenu package, well after this early init().
    }

    // --------------------------------------------------------------- building

    private void ensureUi()
    {
        if (ui != null)
            return;

        ui = new Stage(new ScreenViewport());

        moveBase = skinImage("touchpad-background");
        moveKnob = skinImage("touchpad-move");
        aimBase = skinImage("touchpad-background");
        aimKnob = skinImage("touchpad-aim");

        for (Image img : new Image[]{ moveBase, moveKnob, aimBase, aimKnob })
        {
            img.setVisible(false);
            ui.addActor(img);
        }

        cluster = buildCluster();
        utility = buildUtility();

        ui.addActor(cluster);
        ui.addActor(utility);
    }

    private Image skinImage(String drawable)
    {
        Image img = new Image(BrainOutClient.Skin.getDrawable(drawable));
        img.setTouchable(Touchable.disabled);
        return img;
    }

    private Table buildCluster()
    {
        Table table = new Table();
        table.setFillParent(true);
        table.align(Align.bottomRight);

        float gap = minDim() * AndroidConstants.Touch.BUTTON_PAD;
        float w = minDim() * AndroidConstants.Touch.BUTTON_W;
        float h = minDim() * AndroidConstants.Touch.BUTTON_H;
        float slot = minDim() * AndroidConstants.Touch.SLOT_SIZE;

        // Direct weapon slots, as 1-4 and Q on a keyboard (KeyboardController).
        Table slots = new Table();
        slots.add(button("1", false, () -> sendEvent(SelectSlotEvent.obtain(0, Constants.Properties.SLOT_PRIMARY))))
            .size(slot).pad(gap);
        slots.add(button("2", false, () -> sendEvent(SelectSlotEvent.obtain(1)))).size(slot).pad(gap);
        slots.add(button("3", false, () ->
        {
            if (!sendEvent(SelectSlotEvent.obtain(2)))
            {
                sendEvent(SelectSlotEvent.obtain(0, Constants.Properties.SLOT_SECONDARY));
            }
        })).size(slot).pad(gap);
        slots.add(button("4", false, () -> sendEvent(SelectSlotEvent.obtain(3)))).size(slot).pad(gap);
        slots.add(button(L.get("MENU_TOUCH_LAST_SLOT"), true, () -> sendEvent(SelectPreviousSlotEvent.obtain())))
            .size(slot * 1.6f, slot).pad(gap);

        table.add(slots).colspan(2).right().row();

        table.add(button(L.get("MENU_TOUCH_WEAPON"), false,
            () -> action(GameControllerEvent.Action.switchWeapon))).size(w, h).pad(gap);
        table.add(button(L.get("MENU_TOUCH_RELOAD"), false,
            () -> action(GameControllerEvent.Action.reload))).size(w, h).pad(gap);
        table.row();
        table.add(button(L.get("MENU_TOUCH_CROUCH"), false, () ->
        {
            crouching = !crouching;
            action(crouching ? GameControllerEvent.Action.beginSit : GameControllerEvent.Action.endSit);
        })).size(w, h).pad(gap);
        table.add(button(L.get("MENU_TOUCH_USE"), false,
            () -> action(GameControllerEvent.Action.activate))).size(w, h).pad(gap);

        // Float the cluster above the bottom HUD strip and in from the edge.
        table.padRight(realWidth() * AndroidConstants.Touch.BUTTON_MARGIN_RIGHT);
        table.padBottom(realHeight() * AndroidConstants.Touch.BUTTON_MARGIN_BOTTOM);

        return table;
    }

    private Table buildUtility()
    {
        Table table = new Table();
        table.setFillParent(true);
        table.align(Align.topLeft);

        float gap = minDim() * AndroidConstants.Touch.BUTTON_PAD;
        float w = minDim() * AndroidConstants.Touch.SMALL_BUTTON_W;
        float h = minDim() * AndroidConstants.Touch.SMALL_BUTTON_H;
        float margin = minDim() * AndroidConstants.Touch.UTILITY_MARGIN;

        Table row = new Table();
        // the pause menu, the same thing Escape / Back opens
        row.add(button(L.get("MENU_TOUCH_MENU"), true, () -> action(GameControllerEvent.Action.back)))
            .size(w, h).pad(gap);
        row.add(button(L.get("MENU_TOUCH_PLAYERS"), true, () -> action(GameControllerEvent.Action.openPlayerList)))
            .size(w, h).pad(gap);
        // delayed like the keyboard's chat key, so the menu opens on a clean frame
        row.add(button(L.get("MENU_TOUCH_CHAT"), true,
            () -> sendDelayedEvent(GameControllerEvent.obtain(GameControllerEvent.Action.openChat))))
            .size(w, h).pad(gap);
        row.add(button(L.get("MENU_TOUCH_ZOOM"), true, () -> action(GameControllerEvent.Action.switchZoom)))
            .size(w, h).pad(gap);
        row.add(button(L.get("MENU_TOUCH_MORE"), true, () -> morePanel.setVisible(!morePanel.isVisible())))
            .size(w, h).pad(gap);

        morePanel = new Table();
        morePanel.setVisible(false);
        addMore(morePanel, "MENU_TOUCH_DROP", GameControllerEvent.Action.dropInstrument, w, h, gap);
        addMore(morePanel, "MENU_TOUCH_DROP_AMMO", GameControllerEvent.Action.dropAmmo, w, h, gap);
        addMore(morePanel, "MENU_TOUCH_UNLOAD", GameControllerEvent.Action.unload, w, h, gap);
        addMore(morePanel, "MENU_TOUCH_FIRE_MODE", GameControllerEvent.Action.switchShootMode, w, h, gap);
        addMore(morePanel, "MENU_TOUCH_TEAM", GameControllerEvent.Action.changeTeam, w, h, gap);

        table.add(row).left().row();
        table.add(morePanel).left().padLeft(w * 4 + gap * 8);

        table.padTop(margin);
        table.padLeft(margin);

        return table;
    }

    private void addMore(Table panel, String label, GameControllerEvent.Action action, float w, float h, float gap)
    {
        panel.add(button(L.get(label), true, () ->
        {
            morePanel.setVisible(false);
            action(action);
        })).size(w * 1.3f, h).pad(gap).row();
    }

    private TextButton button(String text, boolean small, Runnable onTap)
    {
        TextButton button = new TextButton(text, BrainOutClient.Skin, "button-small");
        button.getLabel().setFontScale(small
            ? AndroidConstants.Touch.SMALL_FONT_SCALE
            : AndroidConstants.Touch.BUTTON_FONT_SCALE);
        button.getLabel().setWrap(true);
        button.addListener(new ClickListener()
        {
            @Override
            public void clicked(InputEvent event, float x, float y)
            {
                onTap.run();
            }
        });
        return button;
    }

    private void action(GameControllerEvent.Action action)
    {
        sendEvent(GameControllerEvent.obtain(action));
    }

    // ------------------------------------------------------------------ input

    private boolean hudActive()
    {
        return controllerMode == ControllerMode.action
            || controllerMode == ControllerMode.actionWithNoMouseLocking;
    }

    private static float realWidth()
    {
        return Gdx.graphics.getWidth();
    }

    private static float realHeight()
    {
        return Gdx.graphics.getHeight();
    }

    private float minDim()
    {
        return Math.min(realWidth(), realHeight());
    }

    private float radius()
    {
        return minDim() * AndroidConstants.Touch.STICK_RADIUS;
    }

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button)
    {
        if (!hudActive())
            return false;

        // Buttons get first refusal.
        if (hitsButton(screenX, screenY))
        {
            ui.touchDown(screenX, screenY, pointer, button);
            return true;
        }

        // a tap anywhere else closes the "more" panel
        if (morePanel != null && morePanel.isVisible())
        {
            morePanel.setVisible(false);
        }

        boolean left = screenX < realWidth() / 2f;

        if (left && movePointer == NONE)
        {
            movePointer = pointer;
            moveOrigin.set(screenX, screenY);
            moveCur.set(screenX, screenY);
            showStick(moveBase, moveKnob, moveOrigin, moveOrigin);
        }
        else if (!left && aimPointer == NONE)
        {
            aimPointer = pointer;
            aimOrigin.set(screenX, screenY);
            aimCur.set(screenX, screenY);
            showStick(aimBase, aimKnob, aimOrigin, aimOrigin);
        }

        return true;
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointer)
    {
        if (pointer == movePointer)
        {
            moveCur.set(screenX, screenY);
            return true;
        }
        if (pointer == aimPointer)
        {
            aimCur.set(screenX, screenY);
            return true;
        }
        if (hudActive() && ui != null)
        {
            ui.touchDragged(screenX, screenY, pointer);
        }
        return false;
    }

    @Override
    public boolean touchUp(int screenX, int screenY, int pointer, int button)
    {
        if (pointer == movePointer)
        {
            movePointer = NONE;
            moveVec.setZero();
            stopMoving();
            hideStick(moveBase, moveKnob);
            return true;
        }
        if (pointer == aimPointer)
        {
            aimPointer = NONE;
            stopFiring();
            hideStick(aimBase, aimKnob);
            return true;
        }
        if (hudActive() && ui != null)
        {
            ui.touchUp(screenX, screenY, pointer, button);
        }
        return false;
    }

    @Override
    public boolean isOverControl(int screenX, int screenY)
    {
        return hudActive() && ui != null && hitsButton(screenX, screenY);
    }

    private boolean hitsButton(float screenX, float screenY)
    {
        stageTmp.set(screenX, screenY);
        ui.screenToStageCoordinates(stageTmp);
        // the stick images are untouchable and the layout tables only pass
        // touches to their children, so any hit is a button
        Actor hit = ui.hit(stageTmp.x, stageTmp.y, true);
        return hit != null;
    }

    // ----------------------------------------------------------------- update

    @Override
    public void update(float dt)
    {
        if (ui == null)
            return;

        syncViewport();
        ui.act(dt);

        if (!hudActive())
            return;

        updateMove();
        updateAim();
        updateKnobs();
    }

    private void updateMove()
    {
        if (movePointer == NONE)
            return;

        float range = radius() * AndroidConstants.Touch.KNOB_RANGE;
        float dx = clampRange(moveCur.x - moveOrigin.x, range);
        float dy = clampRange(moveCur.y - moveOrigin.y, range);

        float moveTh = AndroidConstants.Touch.MOVE_THRESHOLD * range;

        float x = dx > moveTh ? 1 : (dx < -moveTh ? -1 : 0);
        // screen Y grows downward: finger up (dy < 0) means move up (+1).
        float y = dy < -moveTh ? 1 : (dy > moveTh ? -1 : 0);

        if (x != moveVec.x || y != moveVec.y)
        {
            moveVec.set(x, y);
            sendEvent(GameControllerEvent.obtain(GameControllerEvent.Action.move, moveVec));
        }

        boolean wantRun = len(dx, dy) > AndroidConstants.Touch.RUN_THRESHOLD * range;
        if (wantRun && !running)
        {
            running = true;
            sendEvent(GameControllerEvent.obtain(GameControllerEvent.Action.beginRun));
        }
        else if (!wantRun && running)
        {
            running = false;
            sendEvent(GameControllerEvent.obtain(GameControllerEvent.Action.endRun));
        }
    }

    private void updateAim()
    {
        if (aimPointer == NONE)
            return;

        float range = radius() * AndroidConstants.Touch.KNOB_RANGE;
        float dx = aimCur.x - aimOrigin.x;
        float dy = aimCur.y - aimOrigin.y;
        float frac = len(dx, dy) / range;

        if (frac > AndroidConstants.Touch.AIM_DEAD_ZONE)
        {
            aimMouse.set(dx, dy).nor().scl(AndroidConstants.Touch.AIM_RADIUS_PX);
            sendEvent(GameControllerEvent.obtain(GameControllerEvent.Action.absoluteAim, aimMouse));
        }

        boolean wantFire = frac > AndroidConstants.Touch.FIRE_THRESHOLD;
        if (wantFire && !firing)
        {
            firing = true;
            sendEvent(GameControllerEvent.obtain(GameControllerEvent.Action.beginLaunch, 0));
        }
        else if (!wantFire && firing)
        {
            firing = false;
            sendEvent(GameControllerEvent.obtain(GameControllerEvent.Action.endLaunch, 0));
        }
    }

    private void stopMoving()
    {
        sendEvent(GameControllerEvent.obtain(GameControllerEvent.Action.move, moveVec.setZero()));
        if (running)
        {
            running = false;
            sendEvent(GameControllerEvent.obtain(GameControllerEvent.Action.endRun));
        }
    }

    private void stopFiring()
    {
        if (firing)
        {
            firing = false;
            sendEvent(GameControllerEvent.obtain(GameControllerEvent.Action.endLaunch, 0));
        }
    }

    // ------------------------------------------------------------- knob render

    private void showStick(Image base, Image knob, Vector2 originScreen, Vector2 knobScreen)
    {
        placeCentered(base, originScreen, radius() * 2f);
        placeCentered(knob, knobScreen, radius());
        base.setVisible(true);
        knob.setVisible(true);
    }

    private void hideStick(Image base, Image knob)
    {
        base.setVisible(false);
        knob.setVisible(false);
    }

    private void updateKnobs()
    {
        float range = radius() * AndroidConstants.Touch.KNOB_RANGE;

        if (movePointer != NONE)
        {
            float dx = clampRange(moveCur.x - moveOrigin.x, range);
            float dy = clampRange(moveCur.y - moveOrigin.y, range);
            stageTmp.set(moveOrigin.x + dx, moveOrigin.y + dy);
            placeCentered(moveKnob, stageTmp, radius());
        }
        if (aimPointer != NONE)
        {
            float dx = clampRange(aimCur.x - aimOrigin.x, range);
            float dy = clampRange(aimCur.y - aimOrigin.y, range);
            stageTmp.set(aimOrigin.x + dx, aimOrigin.y + dy);
            placeCentered(aimKnob, stageTmp, radius());
        }
    }

    private void placeCentered(Image img, Vector2 screenPoint, float size)
    {
        stageTmp.set(screenPoint.x, screenPoint.y);
        ui.screenToStageCoordinates(stageTmp);
        img.setSize(size, size);
        img.setPosition(stageTmp.x - size / 2f, stageTmp.y - size / 2f);
    }

    // ----------------------------------------------------------------- helpers

    private static float clampRange(float v, float range)
    {
        if (v > range) return range;
        if (v < -range) return -range;
        return v;
    }

    private static float len(float x, float y)
    {
        return (float) Math.sqrt(x * x + y * y);
    }

    private void syncViewport()
    {
        int w = Gdx.graphics.getWidth();
        int h = Gdx.graphics.getHeight();
        if (w != lastWidth || h != lastHeight)
        {
            ui.getViewport().update(w, h, true);
            lastWidth = w;
            lastHeight = h;
        }
    }

    @Override
    public void setControllerMode(ControllerMode controllerMode)
    {
        super.setControllerMode(controllerMode);
        applyMode();
    }

    private void applyMode()
    {
        boolean active = hudActive();

        if (active)
            ensureUi();

        if (ui == null)
            return;

        cluster.setVisible(active);
        utility.setVisible(active);

        if (!active)
        {
            morePanel.setVisible(false);

            // Release anything held so we don't get stuck moving/firing.
            if (movePointer != NONE || aimPointer != NONE)
            {
                stopMoving();
                stopFiring();
            }
            movePointer = NONE;
            aimPointer = NONE;
            hideStick(moveBase, moveKnob);
            hideStick(aimBase, aimKnob);
        }
    }

    @Override
    public void render()
    {
        if (ui != null)
            ui.draw();
    }

    @Override
    public boolean keyDown(int keycode)
    {
        // Back in a match: the pause menu, as Escape does on a desktop
        // (menus see Back first and close themselves, see Menu.keyDown).
        if (keycode == Input.Keys.BACK && hudActive())
        {
            action(GameControllerEvent.Action.back);
            return true;
        }

        return (ui != null && ui.keyDown(keycode)) || super.keyDown(keycode);
    }

    @Override
    public boolean keyUp(int keycode)
    {
        return (ui != null && ui.keyUp(keycode)) || super.keyUp(keycode);
    }

    @Override
    public boolean keyTyped(char character)
    {
        return (ui != null && ui.keyTyped(character)) || super.keyTyped(character);
    }

    @Override
    public boolean scrolled(float amountX, float amountY)
    {
        return (ui != null && ui.scrolled(amountX, amountY)) || super.scrolled(amountX, amountY);
    }
}
