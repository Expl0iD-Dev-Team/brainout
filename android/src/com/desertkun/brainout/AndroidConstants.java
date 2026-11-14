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

        // --- Buttons ------------------------------------------------------
        // Round icon buttons; sizes are fractions of the smaller screen side
        // (on a 1080 px tall phone 0.1 is 108 px, about 7 mm).
        public static final float FIRE_SIZE = 0.17f;      // the fire button, bottom right
        public static final float ACTION_SIZE = 0.115f;   // reload, use, crouch, weapon
        public static final float SLOT_SIZE = 0.08f;      // weapon slots 1-4 + last
        public static final float UTILITY_SIZE = 0.085f;  // menu, players, chat, zoom, more
        public static final float BUTTON_GAP = 0.014f;
        public static final float EDGE_MARGIN = 0.025f;
        // Bottom of the fire button as a fraction of the screen height: above
        // the weapon / ammo / health panel in the bottom-right corner.
        public static final float CLUSTER_BOTTOM = 0.17f;
        // Share of the circle the pictogram takes.
        public static final float ICON_SCALE = 0.66f;

        public static final float SLOT_FONT_SCALE = 1.2f;
        public static final float SMALL_FONT_SCALE = 1.1f;

        // The "more" list: button width in utility buttons, and opacity.
        public static final float MORE_WIDTH = 2.6f;
        public static final float MORE_ALPHA = 0.85f;
    }
}
