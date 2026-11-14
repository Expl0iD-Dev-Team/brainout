package com.desertkun.brainout.menu.impl;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.ArrayMap;
import com.desertkun.brainout.BrainOutClient;
import com.desertkun.brainout.menu.Popup;
import com.desertkun.brainout.menu.popups.AlertPopup;
import com.desertkun.brainout.menu.ui.ClickOverListener;
import com.desertkun.brainout.utils.Base64;
import com.desertkun.brainout.utils.HashedUrl;

/**
 * Manual "Direct Connect" entry for platforms without command-line arguments
 * (Android). Lets the player type a server host and the three KryoNet ports,
 * encodes {@code host;tcp;udp;http} to base64 and feeds it through the very same
 * {@link HashedUrl} -> {@link com.desertkun.brainout.client.ClientController#connect}
 * path used by the {@code brainout://} deep link.
 */
public class DirectConnectMenu extends Popup
{
    // Default offline-server ports (tcp;udp;http), see docs/AndroidPort.md.
    private static final String DEFAULT_TCP = "36555";
    private static final String DEFAULT_UDP = "36556";
    private static final String DEFAULT_HTTP = "36557";

    private TextField hostEdit;
    private TextField tcpEdit;
    private TextField udpEdit;
    private TextField httpEdit;

    public DirectConnectMenu()
    {
        super("");

        ArrayMap<String, PopupButtonStyle> buttons = new ArrayMap<>();

        buttons.put("Cancel", new PopupButtonStyle(new ClickOverListener()
        {
            @Override
            public void clicked(InputEvent event, float x, float y)
            {
                playSound(MenuSound.back);

                pop();
            }
        }));

        buttons.put("Connect", new PopupButtonStyle(new ClickOverListener()
        {
            @Override
            public void clicked(InputEvent event, float x, float y)
            {
                playSound(MenuSound.select);

                connect();
            }
        }));

        setButtons(buttons);
    }

    @Override
    public String getTitle()
    {
        return "Direct Connect";
    }

    @Override
    protected void initContent(Table data)
    {
        hostEdit = newField("");
        tcpEdit = newField(DEFAULT_TCP);
        udpEdit = newField(DEFAULT_UDP);
        httpEdit = newField(DEFAULT_HTTP);

        addRow(data, "Host / IP", hostEdit);
        addRow(data, "TCP port", tcpEdit);
        addRow(data, "UDP port", udpEdit);
        addRow(data, "HTTP port", httpEdit);

        setKeyboardFocus(hostEdit);
    }

    private TextField newField(String value)
    {
        return new TextField(value, BrainOutClient.Skin, "edit-default");
    }

    private void addRow(Table data, String label, TextField edit)
    {
        Label caption = new Label(label, BrainOutClient.Skin, "title-small");
        caption.setAlignment(Align.left);

        data.add(caption).left().padRight(16).padBottom(8);
        data.add(edit).width(280).height(35).padBottom(8).row();
    }

    private void connect()
    {
        String host = hostEdit.getText().trim();

        if (host.isEmpty())
        {
            pushMenu(new AlertPopup("MENU_WRONG_LINK"));
            return;
        }

        int tcp;
        int udp;
        int http;

        try
        {
            tcp = Integer.parseInt(tcpEdit.getText().trim());
            udp = Integer.parseInt(udpEdit.getText().trim());
            http = Integer.parseInt(httpEdit.getText().trim());
        }
        catch (NumberFormatException e)
        {
            pushMenu(new AlertPopup("MENU_WRONG_LINK"));
            return;
        }

        // Encode exactly like the brainout:// deep link, then decode through the
        // same HashedUrl so both entry points share one connection code path.
        String payload = host + ";" + tcp + ";" + udp + ";" + http;
        String encoded = Base64.encode(payload.getBytes());

        HashedUrl url = new HashedUrl();

        if (!url.unhash(encoded))
        {
            pushMenu(new AlertPopup("MENU_WRONG_LINK"));
            return;
        }

        pop();

        Gdx.app.postRunnable(() ->
            BrainOutClient.ClientController.connect(
                url.getLocation(), url.getTcp(), url.getUdp(), url.getHttp(),
                null, false, -1,
                () -> pushMenu(new AlertPopup("MENU_CONNECTION_ERROR"))));
    }
}
