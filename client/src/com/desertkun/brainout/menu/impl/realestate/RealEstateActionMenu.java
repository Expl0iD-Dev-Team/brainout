package com.desertkun.brainout.menu.impl.realestate;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.utils.TimeUtils;
import com.desertkun.brainout.BrainOutClient;
import com.desertkun.brainout.ClientConstants;
import com.desertkun.brainout.Constants;
import com.desertkun.brainout.client.states.CSGame;
import com.desertkun.brainout.components.ClientHighlightComponent;
import com.desertkun.brainout.components.ClientPlayerComponent;
import com.desertkun.brainout.content.components.RealEstateItemContainerComponent;
import com.desertkun.brainout.controllers.GameController;
import com.desertkun.brainout.data.FreePlayMap;
import com.desertkun.brainout.data.Map;
import com.desertkun.brainout.data.active.ActiveData;
import com.desertkun.brainout.data.active.PlayerData;
import com.desertkun.brainout.data.block.BlockData;
import com.desertkun.brainout.data.components.*;
import com.desertkun.brainout.menu.Menu;

public class RealEstateActionMenu extends Menu
{
    private Vector2 mousePosition = new Vector2();
    private ActiveData hoveredItem;

    @Override
    public Table createUI()
    {
        Table table = new Table();
        return table;
    }

    private void enablePlayerAimMarker(boolean enable)
    {
        CSGame game = BrainOutClient.ClientController.getState(CSGame.class);
        if (game == null)
            return;

        PlayerData playerData = game.getPlayerData();
        if (playerData == null)
            return;

        ClientPlayerComponent pca = playerData.getComponent(ClientPlayerComponent.class);
        if (pca == null)
            return;

        pca.setForceDisplayAim(enable);
        pca.setDisplayAim(enable);
    }

    @Override
    public void onFocusIn()
    {
        BrainOutClient.Env.getGameController().setControllerMode(GameController.ControllerMode.actionWithNoMouseLocking);
        BrainOutClient.Env.getGameController().reset();

        enablePlayerAimMarker(false);

        if (ClientConstants.Client.MOUSE_LOCK)
        {
            Gdx.input.setCursorCatched(false);
        }
    }

    private Vector2 convertMousePosition(Map map, float screenX, float screenY)
    {
        // screenX/Y are real pixels; the world camera uses UI units (see BrainOutClient.getUIScale)
        Map.GetMouseScaleWatcher(BrainOutClient.toUIX(screenX) - BrainOutClient.getWidth() / 2f,
                - (BrainOutClient.toUIY(screenY) - BrainOutClient.getHeight() / 2f), mousePosition);

        mousePosition.x = MathUtils.clamp(mousePosition.x, 0, map.getWidth());
        mousePosition.y = MathUtils.clamp(mousePosition.y, 0, map.getHeight());

        return mousePosition;
    }

    private ActiveData checkBlock(Map map, Vector2 position, int layer)
    {
        BlockData b = map.getBlock((int)position.x, (int)position.y, layer);

        if (b != null)
        {
            SpriteBlockComponentData cp = b.getComponent(SpriteBlockComponentData.class);

            if (cp != null)
            {
                return cp.getSprite(map);
            }
        }

        return null;
    }

    private ActiveData checkBlock(Map map, Vector2 position)
    {
        ActiveData activeData = checkBlock(map, position, Constants.Layers.BLOCK_LAYER_FOREGROUND);

        if (activeData != null)
        {
            return activeData;
        }

        return checkBlock(map, position, Constants.Layers.BLOCK_LAYER_BACKGROUND);
    }

    @Override
    public boolean mouseMoved(int screenX, int screenY)
    {
        hoveredItem = getHoverItem(screenX, screenY);

        FreePlayMap map = getMap();
        if (map == null)
            return false;

        if (hoveredItem != null && hoveredItem.getCreator().hasComponent(RealEstateItemContainerComponent.class))
        {
            map.getHighlightComponent().setHighlight(hoveredItem);
        }
        else
        {
            map.getHighlightComponent().setHighlight(null);
        }

        return super.mouseMoved(screenX, screenY);
    }

    private FreePlayMap getMap()
    {
        CSGame game = BrainOutClient.ClientController.getState(CSGame.class);
        if (game == null)
            return null;

        PlayerData playerData = game.getPlayerData();
        if (playerData == null)
            return null;

        FreePlayMap map = playerData.getMap(FreePlayMap.class);

        if (map == null)
            return null;

        return map;
    }

    private ActiveData getHoverItem(int screenX, int screenY)
    {
        FreePlayMap map = getMap();
        if (map == null)
            return null;

        return checkBlock(map, convertMousePosition(map, screenX, screenY));
    }

    // A touch screen has no hover and no right button. There a short tap that
    // barely moves picks the container under the finger and opens it, the way a
    // right click on a hovered one does; a longer touch stays a stick gesture.
    private static final long TAP_MAX_MS = 300;
    private static final float TAP_MAX_MOVE = 0.03f; // fraction of the smaller screen side

    private int tapPointer = -1;
    private long tapTime;
    private int tapX, tapY;

    private static boolean isTouchScreen()
    {
        return Gdx.app.getType() == Application.ApplicationType.Android;
    }

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button)
    {
        if (isTouchScreen())
        {
            if (tapPointer == -1 &&
                !BrainOutClient.Env.getGameController().isOverControl(screenX, screenY))
            {
                tapPointer = pointer;
                tapTime = TimeUtils.millis();
                tapX = screenX;
                tapY = screenY;
            }
        }
        else if (button == Input.Buttons.RIGHT)
        {
            openHoveredContainer();
        }

        return super.touchDown(screenX, screenY, pointer, button);
    }

    @Override
    public boolean touchUp(int screenX, int screenY, int pointer, int button)
    {
        if (isTouchScreen() && pointer == tapPointer)
        {
            tapPointer = -1;

            float maxMove = TAP_MAX_MOVE * Math.min(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());

            if (TimeUtils.timeSinceMillis(tapTime) <= TAP_MAX_MS &&
                Vector2.dst(tapX, tapY, screenX, screenY) <= maxMove)
            {
                hoveredItem = getHoverItem(screenX, screenY);
                openHoveredContainer();
            }
        }

        return super.touchUp(screenX, screenY, pointer, button);
    }

    private void openHoveredContainer()
    {
        if (hoveredItem != null)
        {
            CSGame csGame = BrainOutClient.ClientController.getState(CSGame.class);
            if (csGame != null)
            {
                PlayerData playerData = csGame.getPlayerData();
                if (playerData != null)
                {
                    ActiveProgressVisualComponentData progress = playerData.getComponent(ActiveProgressVisualComponentData.class);

                    if (progress != null && progress.isActive())
                    {
                        return;
                    }
                }
            }

            SpriteWithBlocksComponentData spi = hoveredItem.getComponent(SpriteWithBlocksComponentData.class);

            if (spi != null && Map.Get(hoveredItem.getDimension(), FreePlayMap.class) != null)
            {
                RealEstateItemContainerComponent rsnic =
                    hoveredItem.getCreator().getComponent(RealEstateItemContainerComponent.class);
                if (rsnic != null)
                {
                    BrainOutClient.getInstance().topState().topMenu().pushMenu(
                        new RealEstateItemExchangeInventoryMenu(
                            BrainOutClient.ClientController.getState(CSGame.class).getPlayerData(),
                            hoveredItem
                    ));
                }
            }
        }
    }

    @Override
    public void onFocusOut(Menu toMenu)
    {
        super.onFocusOut(toMenu);

        BrainOutClient.Env.getGameController().setControllerMode(GameController.ControllerMode.disabled);

        enablePlayerAimMarker(true);

        if (ClientConstants.Client.MOUSE_LOCK)
        {
            Gdx.input.setCursorCatched(true);
        }
    }
}
