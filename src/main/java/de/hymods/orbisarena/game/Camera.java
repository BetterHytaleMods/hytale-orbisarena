package de.hymods.orbisarena.game;

import com.hypixel.hytale.protocol.ClientCameraView;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.MouseInputType;
import com.hypixel.hytale.protocol.MovementForceRotationType;
import com.hypixel.hytale.protocol.PositionDistanceOffsetType;
import com.hypixel.hytale.protocol.RotationType;
import com.hypixel.hytale.protocol.ServerCameraSettings;
import com.hypixel.hytale.protocol.packets.camera.SetServerCamera;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import org.joml.Vector3f;

/**
 * The view from above (key 5 switches to it and back): the camera hangs {@code distance} blocks behind and
 * above the flying player, tilted down by {@code pitch} degrees, always facing the enemy, with the mouse cursor
 * shown; WASD moves along the screen. Without it the player simply flies with the game's own camera. Operators
 * tune it with {@code /arena cam <distance> <pitch>}.
 */
public record Camera(double distance, double pitch) {
    public static final Camera STANDARD = new Camera(16, 60);

    public void apply(PlayerRef player, int side) {
        float yaw = side == 0 ? (float) Math.PI : 0f;
        ServerCameraSettings settings = new ServerCameraSettings();
        settings.positionLerpSpeed = 0.25f;
        settings.rotationLerpSpeed = 0.25f;
        settings.distance = (float) this.distance;
        settings.displayCursor = true;
        settings.displayReticle = false;
        settings.isFirstPerson = false;
        settings.eyeOffset = true;
        settings.positionDistanceOffsetType = PositionDistanceOffsetType.DistanceOffset;
        settings.rotationType = RotationType.Custom;
        settings.rotation = new Direction(yaw, (float) -Math.toRadians(this.pitch), 0f);
        settings.movementForceRotationType = MovementForceRotationType.Custom;
        settings.movementForceRotation = new Direction(yaw, 0f, 0f);
        settings.mouseInputType = MouseInputType.LookAtPlane;
        settings.planeNormal = new Vector3f(0f, 1f, 0f);
        player.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, true, settings));
    }

    public static void reset(PlayerRef player) {
        player.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, false, null));
    }
}
