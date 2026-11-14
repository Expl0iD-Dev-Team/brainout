package com.desertkun.brainout;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.utils.Pools;

/**
 * Text entry for scene2d text fields through Android's own input dialog.
 *
 * The default on-screen keyboard is drawn over the game in landscape and covers
 * the field being edited (and its OK button), and typing went in front of any
 * existing text. The dialog shows the whole current text, lets the player edit
 * it freely and puts the result back into the field. Confirming also sends Enter
 * to the field, which is what chat fields send messages on.
 */
public class AndroidTextInput implements TextField.OnscreenKeyboard
{
    private final TextField field;
    private boolean open;

    public static void attach(TextField field)
    {
        if (!(field.getOnscreenKeyboard() instanceof AndroidTextInput))
        {
            field.setOnscreenKeyboard(new AndroidTextInput(field));
        }
    }

    private AndroidTextInput(TextField field)
    {
        this.field = field;
    }

    @Override
    public void show(boolean visible)
    {
        if (!visible || open || field.isDisabled())
            return;

        open = true;

        String hint = field.getMessageText() != null ? field.getMessageText() : "";

        Gdx.input.getTextInput(new Input.TextInputListener()
        {
            @Override
            public void input(String text)
            {
                Gdx.app.postRunnable(() ->
                {
                    open = false;
                    apply(text);
                });
            }

            @Override
            public void canceled()
            {
                Gdx.app.postRunnable(() -> open = false);
            }
        }, hint, field.getText(), hint);
    }

    private void apply(String text)
    {
        // setText runs the field's filter and length limit; with programmatic
        // change events on, listeners see the change as if it had been typed
        field.setProgrammaticChangeEvents(true);
        field.setText(text);
        field.setProgrammaticChangeEvents(false);
        field.setCursorPosition(field.getText().length());

        if (field.getStage() != null && field.getStage().getKeyboardFocus() == field)
        {
            InputEvent enter = Pools.obtain(InputEvent.class);
            enter.setType(InputEvent.Type.keyTyped);
            enter.setCharacter('\n');
            enter.setStage(field.getStage());
            field.fire(enter);
            Pools.free(enter);
        }
    }
}
