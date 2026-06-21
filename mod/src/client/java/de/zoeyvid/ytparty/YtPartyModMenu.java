package de.zoeyvid.ytparty;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import de.zoeyvid.ytparty.gui.PlaylistScreen;

public final class YtPartyModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return PlaylistScreen::new;
    }
}
