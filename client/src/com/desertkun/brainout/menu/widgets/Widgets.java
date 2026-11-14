package com.desertkun.brainout.menu.widgets;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Scaling;
import com.badlogic.gdx.utils.viewport.ScalingViewport;
import com.desertkun.brainout.BrainOutClient;

public class Widgets extends Stage
{
    private Array<Widget> widgets;

    public Widgets()
    {
        // same space as Menu: UI units stretched over the real screen
        super(new ScalingViewport(Scaling.stretch, BrainOutClient.getWidth(), BrainOutClient.getHeight(),
            new OrthographicCamera()));

        getViewport().update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);

        widgets = new Array<>();
    }

    @Override
    public boolean setKeyboardFocus(com.badlogic.gdx.scenes.scene2d.Actor actor)
    {
        // the chat field lives here
        if (actor instanceof com.badlogic.gdx.scenes.scene2d.ui.TextField)
        {
            BrainOutClient.Env.prepareTextField((com.badlogic.gdx.scenes.scene2d.ui.TextField) actor);
        }

        return super.setKeyboardFocus(actor);
    }

    public void addWidget(Widget widget)
    {
        addActor(widget);
        widget.setStage(this);
        widgets.add(widget);

        widget.init();
    }

    public Array<Widget> getWidgets()
    {
        return widgets;
    }

    public void removeWidget(Widget widget)
    {
        widget.setStage(null);
        widget.release();
        widget.remove();

        widgets.removeValue(widget, true);
    }

    public void removeAll()
    {
        for (Widget widget : widgets)
        {
            widget.setStage(null);
            widget.release();
            widget.remove();
        }

        widgets.clear();
    }

    public void release()
    {
        for (Widget widget : widgets)
        {
            widget.release();
        }

        widgets.clear();
    }

    public void render()
    {
        draw();
    }

    public void update(float dt)
    {
        act(dt);
    }
}
