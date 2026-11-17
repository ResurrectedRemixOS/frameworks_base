/*
 * SPDX-FileCopyrightText: 2026 Project Catalyxx
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.catalyxx;

import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Color;
import android.provider.Settings;

/**
 * RR's Liquid Glass, on Catalyxx's system glass: blurred, glass-like SystemUI panels, set up on
 * Remixer's Liquid Glass page.
 *
 * <p>All keys are {@link Settings.System} ints named rr_glass_*, which Remixer's backup covers:
 * read them with {@link #getInt} and watch them with {@link #observe}. SystemUI and the system
 * read the device owner's.
 * {@link #MATERIAL} picks the glass, and wherever glass is off a place looks the way it does
 * without it. Glass never forces blur on: blur still follows
 * {@link Settings.Global#DISABLE_WINDOW_BLURS}, the thermal limit and (unless
 * {@link #SOLID_IN_BATTERY_SAVER} is off) Battery Saver.
 */
public final class GlassSettings {

    /**
     * RR's material: {@link #MATERIAL_SOLID}, {@link #MATERIAL_FROSTED} (the default) or
     * {@link #MATERIAL_LIQUID}.
     */
    public static final String MATERIAL = "rr_glass_material";

    /** No glass, and the system's blur is off. */
    public static final int MATERIAL_SOLID = 0;
    /** Plain blur without a rim ({@link #STYLE_FLAT}). */
    public static final int MATERIAL_FROSTED = 1;
    /** Glass with a curved rim, its middle as {@link #STYLE} says. */
    public static final int MATERIAL_LIQUID = 2;

    public static final int DEFAULT_MATERIAL = MATERIAL_FROSTED;

    /** Glass is on unless the material is Solid, so this is the key to watch for it. */
    public static final String ENABLED = MATERIAL;

    /** How much glass blurs what's behind it, from 0 (light haze) to 100 (fully frosted). */
    public static final String BLUR_STRENGTH = "rr_glass_blur";

    /**
     * The color glass tints what it blurs with: {@link #COLOR_FROSTED}, {@link #COLOR_THEME} or
     * {@link #COLOR_ANDROID}. Unset, Liquid is frosted white and Frosted keeps Android's own
     * tint, so it looks the way Android's blur does until a color is picked. Read it with
     * {@link #color}.
     */
    public static final String COLOR = "rr_glass_color";

    /** Frosted white glass. */
    public static final int COLOR_FROSTED = 0;
    /** Glass in a light shade of the theme's accent color. */
    public static final int COLOR_THEME = 1;
    /**
     * Android's own tint for blurred panels. {@link #TINT} keeps it as it is at
     * {@link #DEFAULT_TINT}, and takes it to clear and solid at either end.
     */
    public static final int COLOR_ANDROID = 2;
    /** {@link #COLOR} while no color is picked. */
    public static final int COLOR_UNSET = -1;

    /** How much the glass color covers what's behind, from 0 (clear) to 100 (solid). */
    public static final String TINT = "rr_glass_color_tint";

    // How glass with a curved rim is drawn, see look(). So far SurfaceFlinger draws the rim on
    // the volume dialog, the lock screen's buttons, the power menu, SystemUI's bottom sheets and
    // notifications over the lock screen or an app; Quick Settings tiles and notifications in the
    // shade draw its light themselves, over the shade's blur (SystemUI's GlassRimShader); other
    // places are plain blur.

    /**
     * The Liquid material's middle: {@link #STYLE_CLEAR} (the default) or
     * {@link #STYLE_FROSTED}. The Frosted material is always {@link #STYLE_FLAT}. Read the style
     * with {@link #style}.
     */
    public static final String STYLE = "rr_glass_style";

    /** Plain blur without a rim. */
    public static final int STYLE_FLAT = 0;
    /**
     * A clear middle, with a curved rim that bends in what's past the edge. Only for buttons and
     * sliders: places with text on their glass get a frosted middle, so the text reads.
     */
    public static final int STYLE_CLEAR = 1;
    /** The same rim around a frosted middle. */
    public static final int STYLE_FROSTED = 2;

    /**
     * How the edge of Liquid glass with a frosted middle is drawn: {@link #EDGE_RR} (the
     * default) or {@link #EDGE_CATALYXX}. Clear glass looks the same in both.
     */
    public static final String EDGE_STYLE = "rr_glass_edge_style";

    /**
     * RR's lens edge: the rim stays clear around the frosted middle and bends what's under the
     * glass out to its edge, and what shows through is a little richer in color. Glass in a
     * window that blurs all that's behind it, such as a notification in the open shade, has
     * nothing sharp to bend there, so it's frosted to the edge.
     */
    public static final int EDGE_RR = 0;
    /** Catalyxx's: the frost goes right up to the edge, under a thin lit rim. */
    public static final int EDGE_CATALYXX = 1;

    /** How far the rim reaches past the edge to bend in what's there, from 0 (flat) to 100. */
    public static final String BEND = "rr_glass_refraction";

    /** Width of the curved rim, in dp. */
    public static final String RIM_WIDTH = "rr_glass_rim_width";

    /** Light on the rim, from 0 (none) to 100. */
    public static final String RIM_LIGHT = "rr_glass_rim_light";

    /** How much the rim splits light into rainbow colors, from 0 (none) to 100. */
    public static final String RAINBOW = "rr_glass_rainbow";

    /** The soft shadow around the glass, from 0 (none) to 100. */
    public static final String SHADOW = "rr_glass_shadow";

    /**
     * 1 (the default) moves the light on the rim as the phone tilts, as if it hung above, see
     * {@link GlassTilt}; 0 keeps it at the top left.
     */
    public static final String TILT = "rr_glass_tilt";

    /**
     * How far the light follows the phone's tilt, from 25 to 200 (percent). 100, the default,
     * keeps it where a light above would be.
     */
    public static final String TILT_AMOUNT = "rr_glass_tilt_amount";

    /**
     * 1 (the default) lets glass with a curved rim turn smoky where what's behind it is light, so
     * what sits on it stays readable; 0 keeps its color everywhere, but for glass off the lock
     * screen in the dark theme, whose white text an app's light page would swallow.
     */
    public static final String ADAPTIVE = "rr_glass_adaptive";

    /** How smoky glass with a curved rim turns over light content, from 0 (not at all) to 100. */
    public static final String SMOKE = "rr_glass_smoke";

    /** {@link #SMOKE_BLACK} (the default) or {@link #SMOKE_THEME}. */
    public static final String SMOKE_COLOR = "rr_glass_smoke_color";

    /** Smoke of plain black. */
    public static final int SMOKE_BLACK = 0;
    /** Smoke in a dark shade of the theme's accent color. */
    public static final int SMOKE_THEME = 1;

    /** How light what's behind has to be before glass turns smoky, from 10 (soon) to 90 (late). */
    public static final String SWITCH_POINT = "rr_glass_switch_point";

    /**
     * 1 (the default) lights glass up under a finger touching it, so far on Quick Settings
     * tiles; 0 turns that off.
     */
    public static final String TOUCH_GLOW = "rr_glass_touch_glow";

    /** {@link #GLOW_WHITE} (the default), {@link #GLOW_ACCENT} or {@link #GLOW_RAINBOW}. */
    public static final String TOUCH_GLOW_COLOR = "rr_glass_touch_glow_color";

    /** White light under the finger. */
    public static final int GLOW_WHITE = 0;
    /** Light in the theme's accent color. */
    public static final int GLOW_ACCENT = 1;
    /** Light that turns through the rainbow around the finger. */
    public static final int GLOW_RAINBOW = 2;

    /** Size of the touch glow, from 50 to 200 (percent of the usual). */
    public static final String TOUCH_GLOW_SIZE = "rr_glass_touch_glow_size";

    /**
     * How glass buttons, cards and the music player squish while pressed and spring back:
     * {@link #SQUISH_OFF}, {@link #SQUISH_SUBTLE}, {@link #SQUISH_NORMAL} (the default) or
     * {@link #SQUISH_BOUNCY}.
     */
    public static final String SQUISH = "rr_glass_squish";

    /** Glass stays still under a finger. */
    public static final int SQUISH_OFF = 0;
    /** A slight squish that settles back without a bounce. */
    public static final int SQUISH_SUBTLE = 1;
    /** A squish that springs back with a little bounce. */
    public static final int SQUISH_NORMAL = 2;
    /** A deeper squish that wobbles as it springs back. */
    public static final int SQUISH_BOUNCY = 3;

    /**
     * A buzz when you tap glass where Android has none of its own: {@link #TAP_BUZZ_OFF} (the
     * default), {@link #TAP_BUZZ_LIGHT} or {@link #TAP_BUZZ_MEDIUM}. It follows the system's
     * touch vibration setting.
     */
    public static final String TAP_BUZZ = "rr_glass_tap_buzz";

    public static final int TAP_BUZZ_OFF = 0;
    /** Android's light tick. */
    public static final int TAP_BUZZ_LIGHT = 1;
    /** Android's click, as for a key. */
    public static final int TAP_BUZZ_MEDIUM = 2;

    /**
     * 1 (the default) fades notifications softly as they scroll up under the shade's header,
     * where notifications have glass; 0 cuts them off there, as Android does.
     */
    public static final String SCROLL_FADE = "rr_glass_scroll_fade";

    /**
     * 1 (the default) puts the picked option of a glass picker, such as the volume dialog's
     * ringer, in a small glass lens that slides between the choices, and turns slider knobs into
     * one while they're dragged; 0 keeps Android's own.
     */
    public static final String SELECTOR = "rr_glass_selector";

    /**
     * How much the glass selector magnifies what's under it: {@link #MAGNIFY_OFF},
     * {@link #MAGNIFY_SUBTLE} (the default) or {@link #MAGNIFY_STRONG}.
     */
    public static final String SELECTOR_MAGNIFY = "rr_glass_selector_magnify";

    public static final int MAGNIFY_OFF = 0;
    public static final int MAGNIFY_SUBTLE = 1;
    public static final int MAGNIFY_STRONG = 2;

    /**
     * 1 (the default) stretches the glass selector and slider knobs like jelly as they move; 0
     * keeps their shape.
     */
    public static final String STRETCH = "rr_glass_stretch";

    /**
     * 1 (the default) turns glass back into plain panels in Battery Saver, like all other blur.
     * 0 keeps blur on in Battery Saver while glass is on; Android has one blur switch for the
     * whole system there, so apps keep their blur too.
     */
    public static final String SOLID_IN_BATTERY_SAVER = "rr_glass_battery_saver";

    /**
     * How the music player looks where notifications have glass: {@link #PLAYER_TINTED} (the
     * default), {@link #PLAYER_CLEAR} or {@link #PLAYER_ALBUM_ART}.
     */
    public static final String MUSIC_PLAYER = "rr_glass_music_player";

    /** Glass tinted with the album art's color. */
    public static final int PLAYER_TINTED = 0;
    /** Clear glass, as notifications have it. */
    public static final int PLAYER_CLEAR = 1;
    /** The album art, as Android shows it, with the glass rim on it. */
    public static final int PLAYER_ALBUM_ART = 2;

    // The lock screen's Glass clock face (GlassClockView): picked in the clock picker, it's glass
    // whether or not glass is on elsewhere, so these work without the main switch.

    /** {@link #CLOCK_CLEAR} (the default), {@link #CLOCK_FROSTED} or {@link #CLOCK_TINTED}. */
    public static final String CLOCK_STYLE = "rr_glass_clock_style";

    /** Clear glass: the wallpaper shows through the digits, a touch lighter. */
    public static final int CLOCK_CLEAR = 0;
    /** Frosted glass: blurred in the middle, clear at the edges so their bend shows. */
    public static final int CLOCK_FROSTED = 1;
    /** Glass tinted with the theme's accent color. */
    public static final int CLOCK_TINTED = 2;

    /** {@link #DEPTH_FLAT}, {@link #DEPTH_BEVELLED} (the default) or {@link #DEPTH_DEEP}. */
    public static final String CLOCK_DEPTH = "rr_glass_clock_depth";

    /** Flat digits with only a thin rounded edge. */
    public static final int DEPTH_FLAT = 0;
    /** Digits with a rounded bevel. */
    public static final int DEPTH_BEVELLED = 1;
    /** Thick, deeply rounded digits. */
    public static final int DEPTH_DEEP = 2;

    /** How much the digits' edges bend what's behind them, from 0 (not at all) to 100. */
    public static final String CLOCK_BEND = "rr_glass_clock_bend";

    /** Light on the digits' edges, from 0 (none) to 100. */
    public static final String CLOCK_LIGHT = "rr_glass_clock_light";

    /** How much the light on the digits' edges splits into rainbow colors, from 0 to 100. */
    public static final String CLOCK_RAINBOW = "rr_glass_clock_rainbow";

    /** The soft shadow under the digits, from 0 (none) to 100. */
    public static final String CLOCK_SHADOW = "rr_glass_clock_shadow";

    /** How heavy the digits are, as a font weight from 100 (thin) to 1000 (black). */
    public static final String CLOCK_WEIGHT = "rr_glass_clock_weight";

    /** How rounded the digits' corners are, from 0 (crisp) to 100 (fully round). */
    public static final String CLOCK_ROUNDNESS = "rr_glass_clock_roundness";

    /** How wide the digits are, from 25 (narrow) to 151 (wide); 100 is the font's usual. */
    public static final String CLOCK_WIDTH = "rr_glass_clock_width";

    /** {@link #CLOCK_ONE_LINE} (the default) or {@link #CLOCK_STACKED}. */
    public static final String CLOCK_LAYOUT = "rr_glass_clock_layout";

    /** The time on one line: 10:07. */
    public static final int CLOCK_ONE_LINE = 0;
    /** The hours above the minutes, both bigger. */
    public static final int CLOCK_STACKED = 1;

    /**
     * 1 (the default) keeps the digits readable over any wallpaper: the glass turns milky over a
     * dark or mid-tone one, which clear glass all but vanishes into, and smokes more over a light
     * one. 0 leaves the glass as {@link #CLOCK_STYLE} says.
     */
    public static final String CLOCK_READABLE = "rr_glass_clock_readable";

    /** Every Glass clock key. Only the clock watches them, so they aren't in {@link #KEYS}. */
    public static final String[] CLOCK_KEYS = {
        CLOCK_STYLE, CLOCK_DEPTH, CLOCK_BEND, CLOCK_LIGHT, CLOCK_RAINBOW, CLOCK_SHADOW,
        CLOCK_WEIGHT, CLOCK_ROUNDNESS, CLOCK_WIDTH, CLOCK_LAYOUT, CLOCK_READABLE,
    };

    // Places glass can show. Each is on by default once glass is on; 0 turns it off there.

    /** Quick Settings and the notification shade. */
    public static final String PLACE_SHADE = "rr_glass_qs";

    /**
     * Notifications and the music player: in the shade, on the lock screen and as heads-up.
     */
    public static final String PLACE_NOTIFICATIONS = "rr_glass_notifications";

    /** The lock screen's shortcut buttons and fingerprint button. */
    public static final String PLACE_LOCKSCREEN = "rr_glass_lockscreen";

    /** The volume dialog. */
    public static final String PLACE_VOLUME = "rr_glass_volume";

    /**
     * 1 (the default) gives the volume dialog a frosted middle whatever {@link #STYLE} says, like
     * glass with text on it. 0 leaves it to {@link #STYLE}.
     */
    public static final String VOLUME_FROSTED = "rr_glass_volume_frosted";

    /** The power menu. */
    public static final String PLACE_POWER_MENU = "rr_glass_power";

    /**
     * 1 blurs the whole screen behind the power menu when its panel is glass with a curved rim.
     * 0 (the default) only dims it, so the rim has something sharp to bend.
     */
    public static final String POWER_MENU_BLUR_BEHIND = "rr_glass_power_blur_behind";

    /** SystemUI menus and dialogs, such as the Internet, Bluetooth and volume panel sheets. */
    public static final String PLACE_DIALOGS = "rr_glass_menus";

    /** Launcher3's folders, while one is open. */
    public static final String PLACE_FOLDERS = "rr_glass_folders";

    /**
     * 1 (the default) grows panels out of what opened them and shrinks them back into it, where
     * that panel's own switch is on too: the volume panel out of the volume keys
     * ({@link #MORPH_VOLUME}), the power menu out of the power key ({@link #MORPH_POWER_MENU}),
     * tile pop-ups out of their tile ({@link #MORPH_TILES}) and notifications as they open and
     * close ({@link #MORPH_NOTIFICATIONS}). 0 keeps Android's own animations. It needs the main
     * switch, not the panel's glass: a solid panel morphs too.
     */
    public static final String MORPH = "rr_glass_morph";

    /** How panels morph: {@link #MORPH_LIQUID} (the default) or {@link #MORPH_SMOOTH}. */
    public static final String MORPH_STYLE = "rr_glass_morph_style";

    /**
     * Comes out as a thin drop stretched along the edge, then swells a little past its size and
     * settles with a wobble.
     */
    public static final int MORPH_LIQUID = 0;
    /** Grows cleanly, without a wobble. */
    public static final int MORPH_SMOOTH = 1;

    /**
     * How fast panels morph: {@link #MORPH_SLOW}, {@link #MORPH_NORMAL} (the default) or
     * {@link #MORPH_FAST}. Android's animation speed applies on top, and with animations off
     * panels don't morph.
     */
    public static final String MORPH_SPEED = "rr_glass_morph_speed";

    public static final int MORPH_SLOW = 0;
    public static final int MORPH_NORMAL = 1;
    public static final int MORPH_FAST = 2;

    /** 1 (the default): the volume panel grows out of the volume keys. */
    public static final String MORPH_VOLUME = "rr_glass_morph_volume";

    /** 1 (the default): the power menu grows out of the power key. */
    public static final String MORPH_POWER_MENU = "rr_glass_morph_power_menu";

    /**
     * 1 (the default): a Quick Settings tile's pop-up, such as Internet's, grows out of the tile
     * in {@link #MORPH_STYLE} and shrinks back into it. Android grows it out of the tile too; 0
     * keeps Android's motion. Glass on the pop-up and while it grows comes from
     * {@link #PLACE_DIALOGS}.
     */
    public static final String MORPH_TILES = "rr_glass_morph_tiles";

    /**
     * 1 (the default): notifications open and close in {@link #MORPH_STYLE}, the cards around
     * them moving along. Android moves them on a spring too; 0 keeps Android's.
     */
    public static final String MORPH_NOTIFICATIONS = "rr_glass_morph_notifications";

    /**
     * 1: a heads-up notification drips out of the camera hole at the top of the screen, grows
     * into its card in {@link #MORPH_STYLE} and goes back into the hole as it goes. 0 (the
     * default, as it's a stretch goal) keeps Android's slide from above, as screens without a
     * hole at the top do. Not in {@link #MORPH_PLACES}, whose switches are on by default.
     */
    public static final String MORPH_HEADS_UP = "rr_glass_morph_heads_up";

    /**
     * 1: glass melts together like drops where it meets. A panel that morphs stays joined to where
     * it came out of by a neck that stretches and snaps, and a folder opened from the dock pulls
     * away from it the same way. 0 (the default, as it's a stretch goal) keeps glass apart.
     */
    public static final String MELT = "rr_glass_melt";

    /** How far glass reaches to melt: {@link #MELT_SHORT}, {@link #MELT_NORMAL} or longer. */
    public static final String MELT_REACH = "rr_glass_melt_reach";

    public static final int MELT_SHORT = 0;
    public static final int MELT_NORMAL = 1;
    public static final int MELT_LONG = 2;

    /**
     * The middle of the power key and of the volume keys, in pixels down from the top of the
     * screen held upright, to try a phone's values before they go in its overlay. Unset (the
     * default) keeps the phone's own. Not in Remixer, and not in {@link #KEYS}: they're read
     * each time a panel morphs.
     */
    public static final String MORPH_POWER_KEY_Y = "rr_glass_morph_power_key_y";
    public static final String MORPH_VOLUME_KEYS_Y = "rr_glass_morph_volume_keys_y";

    /** Every panel's morph switch. */
    public static final String[] MORPH_PLACES = {
        MORPH_VOLUME, MORPH_POWER_MENU, MORPH_TILES, MORPH_NOTIFICATIONS,
    };

    /** Every place key. */
    public static final String[] PLACES = {
        PLACE_SHADE, PLACE_NOTIFICATIONS, PLACE_LOCKSCREEN, PLACE_VOLUME, PLACE_POWER_MENU,
        PLACE_DIALOGS, PLACE_FOLDERS,
    };

    /** Every glass key but the Glass clock's ({@link #CLOCK_KEYS}), for watching them at once. */
    public static final String[] KEYS = {
        ENABLED, BLUR_STRENGTH, COLOR, TINT, STYLE, EDGE_STYLE, BEND, RIM_WIDTH, RIM_LIGHT,
        RAINBOW, SHADOW,
        TILT, TILT_AMOUNT, ADAPTIVE, SMOKE, SMOKE_COLOR, SWITCH_POINT, TOUCH_GLOW,
        TOUCH_GLOW_COLOR, TOUCH_GLOW_SIZE, SQUISH, TAP_BUZZ, SCROLL_FADE, SELECTOR,
        SELECTOR_MAGNIFY, STRETCH, SOLID_IN_BATTERY_SAVER, MUSIC_PLAYER, PLACE_SHADE,
        PLACE_NOTIFICATIONS, PLACE_LOCKSCREEN, PLACE_VOLUME, VOLUME_FROSTED, PLACE_POWER_MENU,
        PLACE_DIALOGS, POWER_MENU_BLUR_BEHIND, PLACE_FOLDERS, MORPH, MORPH_STYLE, MORPH_SPEED,
        MORPH_VOLUME, MORPH_POWER_MENU, MORPH_TILES, MORPH_NOTIFICATIONS, MORPH_HEADS_UP, MELT,
        MELT_REACH,
    };

    public static final int MIN_BLUR_STRENGTH = 0;
    public static final int MAX_BLUR_STRENGTH = 100;
    /** Keeps each place's own blur radius. */
    public static final int DEFAULT_BLUR_STRENGTH = 50;

    public static final int MIN_TINT = 0;
    public static final int MAX_TINT = 100;
    /** Light enough that what's behind shows clearly, enough to tell glass from a hole. */
    public static final int DEFAULT_TINT = 20;

    public static final int DEFAULT_STYLE = STYLE_CLEAR;
    public static final int DEFAULT_EDGE_STYLE = EDGE_RR;

    // BEND, RIM_LIGHT, RAINBOW and SHADOW all run from 0 to 100
    public static final int MIN_PERCENT = 0;
    public static final int MAX_PERCENT = 100;

    public static final int DEFAULT_BEND = 50;
    public static final int DEFAULT_RIM_LIGHT = 70;
    public static final int DEFAULT_RAINBOW = 50;
    public static final int DEFAULT_SHADOW = 35;

    public static final int MIN_RIM_WIDTH = 4;
    public static final int MAX_RIM_WIDTH = 32;
    public static final int DEFAULT_RIM_WIDTH = 16;

    public static final int MIN_TILT_AMOUNT = 25;
    public static final int MAX_TILT_AMOUNT = 200;
    public static final int DEFAULT_TILT_AMOUNT = 100;

    /** Dark enough that white icons and text on glass over a white page stay readable. */
    public static final int DEFAULT_SMOKE = 45;

    public static final int MIN_SWITCH_POINT = 10;
    public static final int MAX_SWITCH_POINT = 90;
    /** A little past mid-grey, so glass over everyday dark content keeps its own color. */
    public static final int DEFAULT_SWITCH_POINT = 55;

    public static final int MIN_TOUCH_GLOW_SIZE = 50;
    public static final int MAX_TOUCH_GLOW_SIZE = 200;
    public static final int DEFAULT_TOUCH_GLOW_SIZE = 100;

    public static final int DEFAULT_SQUISH = SQUISH_NORMAL;

    public static final int DEFAULT_SELECTOR_MAGNIFY = MAGNIFY_SUBTLE;

    public static final int DEFAULT_CLOCK_STYLE = CLOCK_CLEAR;
    public static final int DEFAULT_CLOCK_DEPTH = DEPTH_BEVELLED;
    // CLOCK_BEND, CLOCK_LIGHT, CLOCK_RAINBOW and CLOCK_SHADOW run from MIN_PERCENT to
    // MAX_PERCENT, like the rim's
    public static final int DEFAULT_CLOCK_BEND = 50;
    public static final int DEFAULT_CLOCK_LIGHT = 70;
    public static final int DEFAULT_CLOCK_RAINBOW = 40;
    public static final int DEFAULT_CLOCK_SHADOW = 35;
    public static final int MIN_CLOCK_WEIGHT = 100;
    public static final int MAX_CLOCK_WEIGHT = 1000;
    /** Heavy, so there's plenty of glass in each stroke to see through. */
    public static final int DEFAULT_CLOCK_WEIGHT = 850;
    public static final int MIN_CLOCK_ROUNDNESS = 0;
    public static final int MAX_CLOCK_ROUNDNESS = 100;
    public static final int DEFAULT_CLOCK_ROUNDNESS = 100;
    public static final int MIN_CLOCK_WIDTH = 25;
    public static final int MAX_CLOCK_WIDTH = 151;
    public static final int DEFAULT_CLOCK_WIDTH = 100;
    public static final int DEFAULT_CLOCK_LAYOUT = CLOCK_ONE_LINE;

    public static final int DEFAULT_MORPH_STYLE = MORPH_LIQUID;
    public static final int DEFAULT_MORPH_SPEED = MORPH_NORMAL;
    public static final int DEFAULT_MELT_REACH = MELT_NORMAL;

    // How close glass starts to reach for other glass at each MELT_REACH, in dp: a neck joins them
    // from a third of it
    private static final float[] MELT_SIZES_DP = {44f, 64f, 96f};

    // How far the rim reaches at BEND 100, in rim widths, and the shadow's darkness at SHADOW 100
    private static final float MAX_BEND = 2.4f;
    private static final float MAX_SHADOW = 0.6f;

    // How much more smoke glass with text on it gets over light content: the default 45 becomes
    // 61, enough for white text on smoke over a white page to read (about 5.7:1)
    private static final float TEXT_SMOKE_SCALE = 1.35f;

    // What a place's blur radius is multiplied by at the lowest and highest strength
    private static final float MIN_BLUR_SCALE = 0.1f;
    private static final float MAX_BLUR_SCALE = 3f;

    private GlassSettings() {}

    /**
     * A glass key's value, for the caller's own user: its settings are cached, so a look can be
     * read whenever a panel opens.
     */
    public static int getInt(ContentResolver resolver, String key, int def) {
        return Settings.System.getInt(resolver, key, def);
    }

    /** Watches {@code keys}. */
    public static void observe(ContentResolver resolver, ContentObserver observer,
            String... keys) {
        for (String key : keys) {
            resolver.registerContentObserver(Settings.System.getUriFor(key), false, observer);
        }
    }

    /** Whether glass is on: the material isn't Solid. */
    public static boolean isEnabled(ContentResolver resolver) {
        return isEnabled(getInt(resolver, MATERIAL, DEFAULT_MATERIAL));
    }

    /** Whether glass is on with {@link #MATERIAL} at {@code material}. */
    public static boolean isEnabled(int material) {
        return material != MATERIAL_SOLID;
    }

    /** Whether the material is Solid, which turns the system's blur off as well. */
    public static boolean isSolid(ContentResolver resolver) {
        return !isEnabled(resolver);
    }

    /** The glass style now, one of the {@code STYLE_} values. */
    public static int style(ContentResolver resolver) {
        return style(getInt(resolver, MATERIAL, DEFAULT_MATERIAL),
                getInt(resolver, STYLE, DEFAULT_STYLE));
    }

    /** The glass style of {@code material} with {@link #STYLE} at {@code middle}. */
    public static int style(int material, int middle) {
        if (material != MATERIAL_LIQUID) {
            return STYLE_FLAT;
        }
        return middle == STYLE_FROSTED ? STYLE_FROSTED : STYLE_CLEAR;
    }

    /** The glass color now, one of the {@code COLOR_} values. */
    public static int color(ContentResolver resolver) {
        return color(getInt(resolver, MATERIAL, DEFAULT_MATERIAL),
                getInt(resolver, COLOR, COLOR_UNSET));
    }

    /** The glass color of {@code material} with {@link #COLOR} at {@code color}. */
    public static int color(int material, int color) {
        if (color == COLOR_FROSTED || color == COLOR_THEME || color == COLOR_ANDROID) {
            return color;
        }
        return material == MATERIAL_LIQUID ? COLOR_FROSTED : COLOR_ANDROID;
    }

    /** Whether glass is on for {@code place}, one of the {@code PLACE_} keys. */
    public static boolean isOn(ContentResolver resolver, String place) {
        return isEnabled(resolver) && getInt(resolver, place, 1) == 1;
    }

    /**
     * How close glass starts to reach for other glass now ({@link #MELT}, {@link #MELT_REACH}), in
     * pixels for a display of {@code density}: 0 while glass doesn't melt.
     */
    public static float meltSize(ContentResolver resolver, float density) {
        if (!isEnabled(resolver) || getInt(resolver, MELT, 0) != 1) {
            return 0f;
        }
        final int reach = getInt(resolver, MELT_REACH, DEFAULT_MELT_REACH);
        return MELT_SIZES_DP[Math.max(MELT_SHORT, Math.min(MELT_LONG, reach))] * density;
    }

    /**
     * Whether the panel at {@code morphPlace}, one of {@link #MORPH_PLACES}, grows out of what
     * opened it now.
     */
    public static boolean morphs(ContentResolver resolver, String morphPlace) {
        return isEnabled(resolver)
                && getInt(resolver, MORPH, 1) == 1
                && getInt(resolver, morphPlace, 1) == 1;
    }

    /** Whether glass at {@code place} has a curved rim: it's on there and not Flat. */
    public static boolean hasRim(ContentResolver resolver, String place) {
        return isOn(resolver, place) && style(resolver) != STYLE_FLAT;
    }

    /**
     * Whether the power menu blurs the whole screen behind it: always as Android does, except
     * that glass with a curved rim only dims it unless {@link #POWER_MENU_BLUR_BEHIND} is on.
     */
    public static boolean blursBehindPowerMenu(ContentResolver resolver) {
        return !hasRim(resolver, PLACE_POWER_MENU)
                || getInt(resolver, POWER_MENU_BLUR_BEHIND, 0) == 1;
    }

    /**
     * What glass multiplies a blur radius by at {@code strength}: 1 at the default 50, falling
     * to 0.1 at 0 and rising to 3 at 100. Each step multiplies it by the same amount, as a blur
     * looks as much stronger from 10 to 20 pixels as from 40 to 80: on a straight line most of
     * the slider's upper half showed nothing.
     */
    public static float blurScale(int strength) {
        final int s = Math.max(MIN_BLUR_STRENGTH, Math.min(MAX_BLUR_STRENGTH, strength));
        if (s <= DEFAULT_BLUR_STRENGTH) {
            return (float) Math.pow(MIN_BLUR_SCALE,
                    1f - s / (float) DEFAULT_BLUR_STRENGTH);
        }
        return (float) Math.pow(MAX_BLUR_SCALE, (s - DEFAULT_BLUR_STRENGTH)
                / (float) (MAX_BLUR_STRENGTH - DEFAULT_BLUR_STRENGTH));
    }

    /** What glass multiplies {@code place}'s blur radius by now: 1 while glass is off there. */
    public static float blurScale(ContentResolver resolver, String place) {
        if (!isOn(resolver, place)) {
            return 1f;
        }
        return blurScale(getInt(resolver, BLUR_STRENGTH, DEFAULT_BLUR_STRENGTH));
    }

    /** {@code radius} multiplied by {@code scale}; a radius above 0 stays above 0. */
    public static int scaleBlurRadius(int radius, float scale) {
        if (radius <= 0) {
            return radius;
        }
        return Math.max(1, Math.round(radius * scale));
    }

    /**
     * The color glass tints {@code place} with over its blur: {@code androidColor}, Android's
     * own tint there, while glass is off there or set to {@link #COLOR_ANDROID}.
     */
    public static int tintColor(Context context, String place, int androidColor) {
        final ContentResolver resolver = context.getContentResolver();
        if (!isOn(resolver, place)) {
            return androidColor;
        }
        return tintColor(context, color(resolver), getInt(resolver, TINT, DEFAULT_TINT),
                androidColor);
    }

    /**
     * The tint of glass color {@code color} at {@code tint}, one of the {@code COLOR_} values
     * and 0 to 100; {@code androidColor} for {@link #COLOR_ANDROID}, made clearer or more solid.
     */
    public static int tintColor(Context context, int color, int tint, int androidColor) {
        final int t = Math.max(MIN_TINT, Math.min(MAX_TINT, tint));
        final int rgb;
        if (color == COLOR_ANDROID) {
            final int alpha = Color.alpha(androidColor);
            if (alpha == 0) {
                return androidColor;
            }
            final float a = t <= DEFAULT_TINT
                    ? alpha * t / (float) DEFAULT_TINT
                    : alpha + (255 - alpha) * (t - DEFAULT_TINT)
                            / (float) (MAX_TINT - DEFAULT_TINT);
            return (Math.round(a) << 24) | (androidColor & 0xffffff);
        } else if (color == COLOR_THEME) {
            // A light accent shade, so it frosts like the white instead of darkening
            rgb = context.getColor(context.getResources().getConfiguration().isNightModeActive()
                    ? android.R.color.system_accent1_200
                    : android.R.color.system_accent1_100);
        } else {
            rgb = Color.WHITE;
        }
        return (Math.round(t * 255f / MAX_TINT) << 24) | (rgb & 0xffffff);
    }

    /**
     * How glass with a curved rim is drawn at {@code place} now, with sizes in pixels for
     * {@code context}'s display; null where it's plain blur, because glass is off there or set
     * to {@link #STYLE_FLAT}. Its color over dark content is {@link #tintColor}'s, with
     * {@code androidColor} as Android's own tint there; the glass draws it itself, so the place
     * leaves its own tint out.
     */
    @Nullable
    public static GlassLook look(Context context, String place, int androidColor) {
        final ContentResolver resolver = context.getContentResolver();
        if (!isOn(resolver, place)) {
            return null;
        }
        final int style = style(resolver);
        final int smoke = getInt(resolver, SMOKE, DEFAULT_SMOKE);
        // Glass with text on it gets a frosted middle whatever the style, and more smoke over
        // light content, so the text on it reads. The volume dialog's sliders read better that
        // way too, unless it's asked not to.
        final boolean carriesText = PLACE_DIALOGS.equals(place)
                || PLACE_POWER_MENU.equals(place) || PLACE_SHADE.equals(place)
                || PLACE_NOTIFICATIONS.equals(place) || PLACE_FOLDERS.equals(place)
                || (PLACE_VOLUME.equals(place)
                        && getInt(resolver, VOLUME_FROSTED, 1) == 1);
        final boolean adapts = getInt(resolver, ADAPTIVE, 1) == 1
                || (!PLACE_LOCKSCREEN.equals(place)
                        && context.getResources().getConfiguration().isNightModeActive());
        return look(context,
                carriesText && style == STYLE_CLEAR ? STYLE_FROSTED : style,
                getInt(resolver, EDGE_STYLE, DEFAULT_EDGE_STYLE),
                getInt(resolver, BEND, DEFAULT_BEND),
                getInt(resolver, RIM_WIDTH, DEFAULT_RIM_WIDTH),
                getInt(resolver, RIM_LIGHT, DEFAULT_RIM_LIGHT),
                getInt(resolver, RAINBOW, DEFAULT_RAINBOW),
                getInt(resolver, SHADOW, DEFAULT_SHADOW),
                tintColor(context, place, androidColor),
                carriesText ? Math.min(MAX_PERCENT, Math.round(smoke * TEXT_SMOKE_SCALE)) : smoke,
                getInt(resolver, SMOKE_COLOR, SMOKE_BLACK),
                adapts ? 1 : 0,
                getInt(resolver, SWITCH_POINT, DEFAULT_SWITCH_POINT));
    }

    /**
     * The look of glass with these {@link #STYLE}, {@link #EDGE_STYLE}, {@link #BEND},
     * {@link #RIM_WIDTH}, {@link #RIM_LIGHT}, {@link #RAINBOW}, {@link #SHADOW}, {@link #SMOKE},
     * {@link #SMOKE_COLOR}, {@link #ADAPTIVE} and {@link #SWITCH_POINT} settings, and
     * {@code tint} as its ARGB color over dark content (see {@link #tintColor}); sizes in pixels
     * for {@code context}'s display, null for {@link #STYLE_FLAT}.
     */
    @Nullable
    public static GlassLook look(Context context, int style, int edgeStyle, int bend,
            int rimWidth, int rimLight, int rainbow, int shadow, int tint, int smoke,
            int smokeColor, int adaptive, int switchPoint) {
        if (style == STYLE_FLAT) {
            return null;
        }
        final int width = Math.max(MIN_RIM_WIDTH, Math.min(MAX_RIM_WIDTH, rimWidth));
        final int smokeRgb = smokeColor == SMOKE_THEME
                ? context.getColor(android.R.color.system_accent1_900)
                : Color.BLACK;
        final int point = Math.max(MIN_SWITCH_POINT, Math.min(MAX_SWITCH_POINT, switchPoint));
        return new GlassLook(
                width * context.getResources().getDisplayMetrics().density,
                MAX_BEND * fraction(bend),
                style == STYLE_FROSTED ? 1f : 0f,
                fraction(rimLight),
                fraction(rainbow),
                MAX_SHADOW * fraction(shadow),
                tint,
                (Math.round(fraction(smoke) * 255) << 24) | (smokeRgb & 0xffffff),
                adaptive == 1 ? point / 100f : 0f,
                style == STYLE_FROSTED && edgeStyle != EDGE_CATALYXX);
    }

    /**
     * How far the light on glass follows the phone's tilt: 1 keeps it where a light above would
     * be, 0 means {@link #TILT} is off.
     */
    public static float tiltAmount(ContentResolver resolver) {
        if (getInt(resolver, TILT, 1) != 1) {
            return 0f;
        }
        final int amount = getInt(resolver, TILT_AMOUNT, DEFAULT_TILT_AMOUNT);
        return Math.max(MIN_TILT_AMOUNT, Math.min(MAX_TILT_AMOUNT, amount)) / 100f;
    }

    /** A 0 to 100 setting as 0 to 1. */
    private static float fraction(int value) {
        return Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, value)) / (float) MAX_PERCENT;
    }

    /** Whether glass keeps the system's blur on while Battery Saver is on. */
    public static boolean keepsBlurInBatterySaver(ContentResolver resolver) {
        return isEnabled(resolver)
                && getInt(resolver, SOLID_IN_BATTERY_SAVER, 1) == 0;
    }
}
