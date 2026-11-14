package com.desertkun.brainout;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Scaling;
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
 * button can all be held at once.
 *
 * The buttons are small round translucent icons (touch-round + touch-icon-*,
 * drawn by scripts/make-touch-icons.py), gathered around the right thumb so
 * the middle of the screen stays free (see layoutHud):
 *
 *   bottom right  big fire button; reload, use and crouch around it; weapon
 *                 switch further left; slots 1-4 + last in a row above
 *   top left      menu (pause), players / inventory, chat, zoom, more
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
    private Group cluster, utility;
    private Table morePanel;
    private Button fire, reload, use, crouch, weapon;
    private Button menu, players, chat, zoom, more;
    private final Button[] slots = new Button[5];
    private Image playersIcon;
    private boolean inventoryMode;

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
    private boolean fireHeld;
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
        ui.addActor(morePanel);

        layoutHud();
    }

    private Image skinImage(String drawable)
    {
        Image img = new Image(BrainOutClient.Skin.getDrawable(drawable));
        img.setTouchable(Touchable.disabled);
        return img;
    }

    private Group buildCluster()
    {
        Group group = new Group();
        // only the buttons take touches, the rest of the screen stays the sticks'
        group.setTouchable(Touchable.childrenOnly);

        // Hold to fire where the weapon points. The aim stick also fires once
        // pulled past FIRE_THRESHOLD, but players tap expecting a shot, and the
        // button lets the right thumb shoot while it is not aiming.
        fire = iconButton("fire", "MENU_TOUCH_FIRE", null);
        fire.addListener(new InputListener()
        {
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, int b)
            {
                fireHeld = true;
                sendEvent(GameControllerEvent.obtain(GameControllerEvent.Action.beginLaunch, 0));
                return true;
            }

            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, int b)
            {
                releaseFireButton();
            }
        });

        reload = iconButton("reload", "MENU_TOUCH_RELOAD", () -> action(GameControllerEvent.Action.reload));
        use = iconButton("use", "MENU_TOUCH_USE", this::use);
        crouch = iconButton("crouch", "MENU_TOUCH_CROUCH", () ->
        {
            crouching = !crouching;
            crouch.setChecked(crouching);
            action(crouching ? GameControllerEvent.Action.beginSit : GameControllerEvent.Action.endSit);
        });
        weapon = iconButton("weapon", "MENU_TOUCH_WEAPON", () -> action(GameControllerEvent.Action.switchWeapon));

        // Direct weapon slots, as 1-4 and Q on a keyboard (KeyboardController).
        slots[0] = textButton("1", () -> sendEvent(SelectSlotEvent.obtain(0, Constants.Properties.SLOT_PRIMARY)));
        slots[1] = textButton("2", () -> sendEvent(SelectSlotEvent.obtain(1)));
        slots[2] = textButton("3", () ->
        {
            if (!sendEvent(SelectSlotEvent.obtain(2)))
            {
                sendEvent(SelectSlotEvent.obtain(0, Constants.Properties.SLOT_SECONDARY));
            }
        });
        slots[3] = textButton("4", () -> sendEvent(SelectSlotEvent.obtain(3)));
        slots[4] = iconButton("last", "MENU_TOUCH_LAST_SLOT", () -> sendEvent(SelectPreviousSlotEvent.obtain()));

        for (Button b : new Button[]{ fire, reload, use, crouch, weapon })
            group.addActor(b);
        for (Button b : slots)
            group.addActor(b);

        return group;
    }

    private Group buildUtility()
    {
        Group group = new Group();
        group.setTouchable(Touchable.childrenOnly);

        // the pause menu, the same thing Escape / Back opens
        menu = iconButton("menu", "MENU_TOUCH_MENU", () -> action(GameControllerEvent.Action.back));
        // In Free Play the same action opens the inventory with the loot around
        // the player (Tab on a keyboard); the icon follows (updateModeLabels).
        players = iconButton("players", "MENU_TOUCH_PLAYERS", () -> action(GameControllerEvent.Action.openPlayerList));
        playersIcon = players.findActor("icon");
        // delayed like the keyboard's chat key, so the menu opens on a clean frame
        chat = iconButton("chat", "MENU_TOUCH_CHAT",
            () -> sendDelayedEvent(GameControllerEvent.obtain(GameControllerEvent.Action.openChat)));
        zoom = iconButton("zoom", "MENU_TOUCH_ZOOM", () -> action(GameControllerEvent.Action.switchZoom));
        more = iconButton("more", "MENU_TOUCH_MORE", () -> morePanel.setVisible(!morePanel.isVisible()));

        for (Button b : new Button[]{ menu, players, chat, zoom, more })
            group.addActor(b);

        // the rarely used actions, as words: there is room, and icons for
        // "drop ammo" or "unload" would only be guessed at
        morePanel = new Table();
        morePanel.setVisible(false);
        addMore(morePanel, "MENU_TOUCH_DROP", GameControllerEvent.Action.dropInstrument);
        addMore(morePanel, "MENU_TOUCH_DROP_AMMO", GameControllerEvent.Action.dropAmmo);
        addMore(morePanel, "MENU_TOUCH_UNLOAD", GameControllerEvent.Action.unload);
        addMore(morePanel, "MENU_TOUCH_FIRE_MODE", GameControllerEvent.Action.switchShootMode);
        addMore(morePanel, "MENU_TOUCH_TEAM", GameControllerEvent.Action.changeTeam);

        return group;
    }

    /**
     * Places every button for the current screen size. Sizes are fractions of
     * the short side (AndroidConstants.Touch); positions are button centres in
     * stage pixels, y up from the bottom.
     */
    private void layoutHud()
    {
        float w = realWidth(), h = realHeight(), r = minDim();

        float big = r * AndroidConstants.Touch.FIRE_SIZE;
        float mid = r * AndroidConstants.Touch.ACTION_SIZE;
        float small = r * AndroidConstants.Touch.UTILITY_SIZE;
        float slot = r * AndroidConstants.Touch.SLOT_SIZE;
        float gap = r * AndroidConstants.Touch.BUTTON_GAP;
        float margin = r * AndroidConstants.Touch.EDGE_MARGIN;

        // the fire button sits in the corner, above the weapon / health panel
        float fx = w - margin - big / 2f;
        float fy = h * AndroidConstants.Touch.CLUSTER_BOTTOM + big / 2f;
        place(fire, fx, fy, big);

        // reload, use and crouch on an arc around it: left, up-left, up
        float around = big / 2f + gap + mid / 2f;
        placeAround(reload, fx, fy, around, 180, mid);
        placeAround(use, fx, fy, around, 135, mid);
        placeAround(crouch, fx, fy, around, 90, mid);
        place(weapon, fx - around - mid - gap, fy, mid);

        // slots in a row above, right-aligned, "last" outermost
        float sy = fy + around + mid / 2f + gap + slot / 2f;
        for (int i = 0; i < slots.length; i++)
        {
            place(slots[i], w - margin - slot / 2f - (slots.length - 1 - i) * (slot + gap), sy, slot);
        }

        // the utility row along the top-left edge
        Button[] row = { menu, players, chat, zoom, more };
        float uy = h - margin - small / 2f;
        for (int i = 0; i < row.length; i++)
        {
            place(row[i], margin + small / 2f + i * (small + gap), uy, small);
        }

        // the "more" list drops down from its button
        float bw = small * AndroidConstants.Touch.MORE_WIDTH;
        float bh = small * 0.62f;
        for (com.badlogic.gdx.scenes.scene2d.ui.Cell<?> cell : morePanel.getCells())
        {
            cell.size(bw, bh).pad(gap / 3f);
        }
        morePanel.invalidate();
        morePanel.pack();
        morePanel.setPosition(more.getX(), more.getY() - gap - morePanel.getHeight());
    }

    private static void place(Actor actor, float cx, float cy, float size)
    {
        actor.setSize(size, size);
        actor.setPosition(cx - size / 2f, cy - size / 2f);
    }

    private static void placeAround(Actor actor, float cx, float cy, float distance, float degrees, float size)
    {
        double a = Math.toRadians(degrees);
        place(actor, cx + (float) Math.cos(a) * distance, cy + (float) Math.sin(a) * distance, size);
    }

    private void addMore(Table panel, String label, GameControllerEvent.Action action)
    {
        TextButton button = new TextButton(L.get(label), BrainOutClient.Skin, "button-small");
        button.getLabel().setFontScale(AndroidConstants.Touch.SMALL_FONT_SCALE);
        button.getLabel().setWrap(true);
        button.getColor().a = AndroidConstants.Touch.MORE_ALPHA;
        button.addListener(new ClickListener()
        {
            @Override
            public void clicked(InputEvent event, float x, float y)
            {
                morePanel.setVisible(false);
                action(action);
            }
        });
        panel.add(button).row();
    }

    private static Button.ButtonStyle roundStyle()
    {
        Button.ButtonStyle style = new Button.ButtonStyle();
        style.up = BrainOutClient.Skin.getDrawable("touch-round");
        style.down = BrainOutClient.Skin.getDrawable("touch-round-down");
        style.checked = style.down;
        return style;
    }

    private static boolean hasIcons()
    {
        return BrainOutClient.getRegion("touch-round") != null;
    }

    /**
     * A round button with a pictogram. Packages built before the icons existed
     * have no touch-* regions; then it is a text button as before.
     */
    private Button iconButton(String icon, String fallbackLabel, Runnable onTap)
    {
        TextureRegion region = BrainOutClient.getRegion("touch-icon-" + icon);

        Button button;

        if (region != null && hasIcons())
        {
            button = new Button(roundStyle());
            Image image = new Image(new TextureRegionDrawable(region));
            image.setName("icon");
            image.setScaling(Scaling.fit);
            image.setTouchable(Touchable.disabled);
            // the glyph takes the inner part of the circle
            button.add(image)
                .width(com.badlogic.gdx.scenes.scene2d.ui.Value.percentWidth(AndroidConstants.Touch.ICON_SCALE, button))
                .height(com.badlogic.gdx.scenes.scene2d.ui.Value.percentHeight(AndroidConstants.Touch.ICON_SCALE, button));
        }
        else
        {
            TextButton text = new TextButton(L.get(fallbackLabel), BrainOutClient.Skin, "button-small");
            text.getLabel().setFontScale(AndroidConstants.Touch.SMALL_FONT_SCALE);
            text.getLabel().setWrap(true);
            button = text;
        }

        if (onTap != null)
        {
            button.addListener(new ClickListener()
            {
                @Override
                public void clicked(InputEvent event, float x, float y)
                {
                    onTap.run();
                }
            });
        }

        return button;
    }

    /** A round button with a number (weapon slots). */
    private Button textButton(String text, Runnable onTap)
    {
        TextButton button;

        if (hasIcons())
        {
            TextButton.TextButtonStyle style = new TextButton.TextButtonStyle();
            Button.ButtonStyle round = roundStyle();
            style.up = round.up;
            style.down = round.down;
            style.font = BrainOutClient.Skin.getFont("medium");
            style.fontColor = com.badlogic.gdx.graphics.Color.WHITE;
            button = new TextButton(text, style);
        }
        else
        {
            button = new TextButton(text, BrainOutClient.Skin, "button-small");
        }

        button.getLabel().setFontScale(AndroidConstants.Touch.SLOT_FONT_SCALE);
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

    private void releaseFireButton()
    {
        if (fireHeld)
        {
            fireHeld = false;
            // the aim stick may still be holding the trigger
            if (!firing)
                sendEvent(GameControllerEvent.obtain(GameControllerEvent.Action.endLaunch, 0));
        }
    }

    /** True where the player-list action opens the inventory and loot (Free Play). */
    private static boolean exchangeInsteadOfPlayers()
    {
        return BrainOutClient.ClientController != null
            && !BrainOutClient.ClientController.canSeePlayerList()
            && BrainOutClient.ClientController.canSeeExchangeMenu();
    }

    private void updateModeLabels()
    {
        boolean inventory = exchangeInsteadOfPlayers();
        if (inventory == inventoryMode || players == null)
            return;

        inventoryMode = inventory;

        if (playersIcon != null)
        {
            TextureRegion region = BrainOutClient.getRegion(inventory ? "touch-icon-inventory" : "touch-icon-players");
            if (region != null)
                playersIcon.setDrawable(new TextureRegionDrawable(region));
        }
        else if (players instanceof TextButton)
        {
            ((TextButton) players).setText(L.get(inventory ? "MENU_TOUCH_INVENTORY" : "MENU_TOUCH_PLAYERS"));
        }
    }

    /**
     * Doors, ladders, safes and the like react to "use" (E on a keyboard).
     * Crates, cupboards and items on the ground do not: on a desktop they are
     * looted from the inventory screen, and players on a phone press "use" at a
     * crate and see nothing happen. So when there is nothing to activate, "use"
     * opens the inventory, which lists the loot within reach.
     */
    private void use()
    {
        if (sendEvent(GameControllerEvent.obtain(GameControllerEvent.Action.activate)))
            return;

        if (exchangeInsteadOfPlayers())
            action(GameControllerEvent.Action.openPlayerList);
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
        // the game mode can arrive after the HUD was shown
        updateModeLabels();
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
            if (!fireHeld)
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
            if (!fireHeld)
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
            layoutHud();
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

        if (active)
            updateModeLabels();

        if (!active)
        {
            morePanel.setVisible(false);

            // Release anything held so we don't get stuck moving/firing.
            if (movePointer != NONE || aimPointer != NONE)
            {
                stopMoving();
                stopFiring();
            }
            releaseFireButton();
            crouching = false;
            if (crouch != null)
                crouch.setChecked(false);
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
