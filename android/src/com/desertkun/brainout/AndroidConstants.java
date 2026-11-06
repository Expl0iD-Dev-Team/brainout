package com.desertkun.brainout;

public class AndroidConstants extends ClientConstants
{
    // Target height of the UI coordinate space, see AndroidEnvironment.getUIScale.
    public static final float UI_HEIGHT = 720.0f;

    public static class Touch
    {
        // --- Floating sticks ---------------------------------------------
        // Stick base radius as a fraction of the smaller screen dimension.
        public static final float STICK_RADIUS = 0.13f;
        // How far the knob may travel from the origin, in stick radii.
        public static final float KNOB_RANGE = 1.0f;

        // Left (move) stick: deflection (fraction of radius) past which an axis
        // counts as a full -1/+1, and past which the player starts running.
        public static final float MOVE_THRESHOLD = 0.35f;
        public static final float RUN_THRESHOLD = 0.85f;

        // Right (aim) stick: deflection before we start aiming, and before we
        // auto-fire. AIM_RADIUS_PX is the screen-space magnitude handed to the
        // absolute-aim path (only the direction matters for the aim angle).
        public static final float AIM_DEAD_ZONE = 0.18f;
        public static final float FIRE_THRESHOLD = 0.45f;
        public static final float AIM_RADIUS_PX = 600f;

        // --- Action buttons ----------------------------------------------
        // Button cell size and gap as a fraction of the smaller screen dim.
        public static final float BUTTON_W = 0.20f;
        public static final float BUTTON_H = 0.10f;
        public static final float BUTTON_PAD = 0.014f;
        public static final float BUTTON_FONT_SCALE = 1.25f;

        // Lift the button cluster off the bottom-right corner so it clears the
        // in-game HP bar / weapon-and-ammo HUD and the system nav bar.
        public static final float BUTTON_MARGIN_BOTTOM = 0.16f; // fraction of height
        public static final float BUTTON_MARGIN_RIGHT = 0.035f; // fraction of width

        // Weapon slot row above the action buttons (square cells).
        public static final float SLOT_SIZE = 0.095f;

        // Utility row in the top-left corner (menu, players, chat, zoom, more)
        // and the "more" panel that drops down from it.
        public static final float SMALL_BUTTON_W = 0.17f;
        public static final float SMALL_BUTTON_H = 0.085f;
        public static final float SMALL_FONT_SCALE = 1.1f;
        public static final float UTILITY_MARGIN = 0.02f; // fraction of the smaller dimension
    }
}
