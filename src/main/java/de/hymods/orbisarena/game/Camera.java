package de.hymods.orbisarena.game;

import com.hypixel.hytale.protocol.ClientCameraView;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.MouseInputType;
import com.hypixel.hytale.protocol.MovementForceRotationType;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.PositionType;
import com.hypixel.hytale.protocol.RotationType;
import com.hypixel.hytale.protocol.ServerCameraSettings;
import com.hypixel.hytale.protocol.packets.camera.SetServerCamera;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import de.hymods.orbisarena.sim.Arena;
import de.hymods.orbisarena.world.Place;
import org.joml.Vector3f;

/**
 * The view from above: a camera fixed behind and above a side's keep, tilted down onto the field, the own side
 * at the bottom of the screen, with the mouse cursor shown. Operators tune it with {@code /arena cam}.
 *
 * @param height  blocks above the floor
 * @param pitch   degrees below the horizon
 * @param lookAt  the tile row (counted from the own back wall) the middle of the screen looks at
 */
public record Camera(double height, double pitch, double lookAt) {
    public static final Camera STANDARD = new Camera(26, 62, 14.5);

    public void apply(PlayerRef player, int side) {
        double back = this.height / Math.tan(Math.toRadians(this.pitch));
        double row = Arena.sideY(side, this.lookAt - back);
        ServerCameraSettings settings = new ServerCameraSettings();
        settings.positionType = PositionType.Custom;
        settings.position = new Position(Place.x(Arena.centerX()), Place.FLOOR + this.height, Place.z(row));
        settings.rotationType = RotationType.Custom;
        // Yaw 0 looks to −z: side 0 looks along +z (towards larger rows), side 1 along −z.
        settings.rotation = new Direction(side == 0 ? (float) Math.PI : 0f, (float) -Math.toRadians(this.pitch), 0f);
        settings.isFirstPerson = false;
        settings.distance = 0f;
        settings.displayCursor = true;
        settings.displayReticle = false;
        settings.hideHeldItem = true;
        settings.positionLerpSpeed = 1f;
        settings.rotationLerpSpeed = 1f;
        settings.movementForceRotationType = MovementForceRotationType.Custom;
        settings.mouseInputType = MouseInputType.LookAtPlane;
        settings.planeNormal = new Vector3f(0f, 1f, 0f);
        player.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, true, settings));
    }

    public static void reset(PlayerRef player) {
        player.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, false, null));
    }
}
