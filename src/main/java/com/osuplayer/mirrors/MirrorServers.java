package com.osuplayer.mirrors;

import java.util.List;

import com.osuplayer.mirrors.servers.BeatconnectMirrorServer;
import com.osuplayer.mirrors.servers.CatboyMirrorServer;
import com.osuplayer.mirrors.servers.NerinyanMirrorServer;
import com.osuplayer.mirrors.servers.RippleMirrorServer;
import com.osuplayer.mirrors.servers.SayobotMirrorServer;
import com.osuplayer.mirrors.servers.YaSOnlineMirrorServer;

public final class MirrorServers {

        private static final List<MirrorServer> ALL = List.of(
            new NerinyanMirrorServer(),
            new CatboyMirrorServer(),
            new SayobotMirrorServer(),
            new RippleMirrorServer(),
            new BeatconnectMirrorServer(),
            new YaSOnlineMirrorServer()
        );

    private MirrorServers() {}

    public static List<MirrorServer> all() {
        return ALL;
    }
}
