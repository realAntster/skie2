package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.PacketEvent;
import myau.events.Render3DEvent;
import myau.events.UpdateEvent;
import myau.module.Module;
import myau.module.modules.Scaffold;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.MSTimer;
import myau.util.PacketUtil;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.*;
import net.minecraft.network.play.server.*;
import net.minecraft.network.status.client.C01PacketPing;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderGlobal;

import java.util.*;

public class BackTrack extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    public final BooleanProperty legit         = new BooleanProperty("Legit", false);
    public final BooleanProperty releaseOnHit  = new BooleanProperty("ReleaseOnHit", true, () -> this.legit.getValue());
    // FIX: default 400ms, min 0, max 1000 — unchanged, but now two separate timers back this up
    public final IntProperty   delay           = new IntProperty("Delay", 400, 0, 1000);
    // FIX: default raised to 6.0 so the slider is actually useful (was 3.0 min == 3.0 default)
    public final FloatProperty hitRange        = new FloatProperty("Range", 6.0f, 3.0f, 10.0f);
    public final BooleanProperty onlyIfNeeded  = new BooleanProperty("OnlyIfNeeded", true);
    public final BooleanProperty esp           = new BooleanProperty("ESP", true);
    public final ModeProperty   espMode        = new ModeProperty("ESPMODE", 0, new String[]{"Hitbox", "None"});

    private final Queue<Packet> incomingPackets = new LinkedList<>();
    private final Queue<Packet> outgoingPackets = new LinkedList<>();
    private final Map<Integer, Vec3> realPositions = new HashMap<>();

    // FIX: two independent timers so releaseIncoming() no longer resets the outgoing timer
    private final MSTimer inTimer  = new MSTimer();
    private final MSTimer outTimer = new MSTimer();

    private EntityLivingBase target;
    private Vec3 lastRealPos;

    public BackTrack() {
        super("BackTrack", false);
    }

    @Override
    public void onEnabled() {
        incomingPackets.clear();
        outgoingPackets.clear();
        realPositions.clear();
        lastRealPos = null;
        inTimer.reset();
        outTimer.reset();
    }

    @Override
    public void onDisabled() {
        releaseAll();
        incomingPackets.clear();
        outgoingPackets.clear();
        realPositions.clear();
        lastRealPos = null;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || mc.thePlayer == null || mc.theWorld == null)
            return;

        Module scaffold = Myau.moduleManager.getModule(Scaffold.class);
        if (scaffold != null && scaffold.isEnabled()) {
            releaseAll();
            return;
        }

        if (event.getType() == EventType.RECEIVE) {
            handleIncoming(event);
        } else if (event.getType() == EventType.SEND) {
            handleOutgoing(event);
        }
    }

    private void handleIncoming(PacketEvent event) {
        Packet<?> packet = event.getPacket();

        // FIX: prune dead entities from realPositions to prevent memory leak
        if (packet instanceof S13PacketDestroyEntities) {
            for (int id : ((S13PacketDestroyEntities) packet).getEntityIDs()) {
                realPositions.remove(id);
            }
        }

        if (packet instanceof S14PacketEntity) {
            S14PacketEntity p = (S14PacketEntity) packet;
            if (p.getEntity(mc.theWorld) == null) return;

            int id = p.getEntity(mc.theWorld).getEntityId();

            // FIX: seed from entity's actual position instead of Vec3(0,0,0)
            // so the very first relative packet doesn't produce a garbage coordinate
            if (!realPositions.containsKey(id)) {
                net.minecraft.entity.Entity entity = p.getEntity(mc.theWorld);
                realPositions.put(id, new Vec3(entity.posX, entity.posY, entity.posZ));
            }

            Vec3 pos = realPositions.get(id);
            realPositions.put(id, pos.addVector(
                    p.func_149062_c() / 32.0,
                    p.func_149061_d() / 32.0,
                    p.func_149064_e() / 32.0
            ));
        }

        if (packet instanceof S18PacketEntityTeleport) {
            S18PacketEntityTeleport p = (S18PacketEntityTeleport) packet;
            realPositions.put(p.getEntityId(),
                    new Vec3(p.getX() / 32.0, p.getY() / 32.0, p.getZ() / 32.0));
        }

        if (shouldQueue() && blockIncoming(packet)) {
            incomingPackets.add(packet);
            event.setCancelled(true);
        } else {
            releaseIncoming();
        }
    }

    private void handleOutgoing(PacketEvent event) {
        if (!legit.getValue()) return;

        Packet<?> packet = event.getPacket();

        // FIX: never queue keepalive or ping — server will kick for timeout
        if (packet instanceof C00PacketKeepAlive || packet instanceof C01PacketPing) {
            return;
        }

        if (shouldQueue() && blockOutgoing(packet)) {
            outgoingPackets.add(packet);
            event.setCancelled(true);
        } else {
            releaseOutgoing();
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent e) {
        if (!isEnabled() || mc.thePlayer == null) return;

        if (mc.objectMouseOver != null && mc.objectMouseOver.entityHit instanceof EntityLivingBase) {
            target = (EntityLivingBase) mc.objectMouseOver.entityHit;
        } else {
            target = null;
        }

        if (target == null) {
            releaseAll();
            return; // FIX: early return — don't fall through to distance checks with null target
        }

        Vec3 real = realPositions.get(target.getEntityId());
        if (real == null) return;

        double distReal    = mc.thePlayer.getDistance(real.xCoord, real.yCoord, real.zCoord);
        double distCurrent = mc.thePlayer.getDistanceToEntity(target);

        // FIX: return after each releaseAll() so we don't triple-fire it
        if (distReal > hitRange.getValue() || inTimer.hasTimePassed(delay.getValue())) {
            releaseAll();
            return;
        }

        if (onlyIfNeeded.getValue() && distCurrent <= distReal) {
            releaseAll();
            return;
        }

        if (legit.getValue() && releaseOnHit.getValue() && target.hurtTime == 1) {
            releaseAll();
            return;
        }

        lastRealPos = real;
    }

    @EventTarget
    public void onRender3D(Render3DEvent e) {
        if (!esp.getValue() || target == null || espMode.getValue() != 0)
            return;

        Vec3 real = realPositions.get(target.getEntityId());
        if (real == null) return;

        double x = real.xCoord - mc.getRenderManager().viewerPosX;
        double y = real.yCoord - mc.getRenderManager().viewerPosY;
        double z = real.zCoord - mc.getRenderManager().viewerPosZ;

        AxisAlignedBB box = new AxisAlignedBB(
                x - target.width / 2,
                y,
                z - target.width / 2,
                x + target.width / 2,
                y + target.height,
                z + target.width / 2
        );

        // FIX: wrap in try/finally so popMatrix() is guaranteed even if something throws mid-render
        GlStateManager.pushMatrix();
        try {
            GlStateManager.disableTexture2D();
            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);

            GlStateManager.color(1F, 0F, 0F, 0.4F);
            RenderGlobal.drawOutlinedBoundingBox(box, 255, 0, 0, 150);

            GlStateManager.depthMask(true);
            GlStateManager.enableDepth();
            GlStateManager.enableTexture2D();
        } finally {
            GlStateManager.popMatrix();
        }
    }

    private boolean shouldQueue() {
        if (target == null) return false;

        Vec3 real = realPositions.get(target.getEntityId());
        if (real == null) return false;

        double distReal    = mc.thePlayer.getDistance(real.xCoord, real.yCoord, real.zCoord);
        double distCurrent = mc.thePlayer.getDistanceToEntity(target);

        return distReal + 0.5 < distCurrent
                && distReal < hitRange.getValue()
                && inTimer.getElapsedTime() < delay.getValue() * 0.85;
    }

    private void releaseIncoming() {
        if (mc.getNetHandler() == null) return;

        while (!incomingPackets.isEmpty()) {
            incomingPackets.poll().processPacket(mc.getNetHandler());
        }
        inTimer.reset(); // FIX: only resets the incoming timer
    }

    private void releaseOutgoing() {
        while (!outgoingPackets.isEmpty()) {
            PacketUtil.sendPacketNoEvent(outgoingPackets.poll());
        }
        outTimer.reset(); // FIX: only resets the outgoing timer
    }

    private void releaseAll() {
        releaseIncoming();
        releaseOutgoing();
    }

    private boolean blockIncoming(Packet<?> p) {
        return p instanceof S14PacketEntity
                || p instanceof S18PacketEntityTeleport
                || p instanceof S19PacketEntityHeadLook
                || p instanceof S0FPacketSpawnMob
                || p instanceof S12PacketEntityVelocity
                || p instanceof S27PacketExplosion;
    }

    private boolean blockOutgoing(Packet<?> p) {
        // FIX: C00PacketKeepAlive and C01PacketPing removed — handled upstream in handleOutgoing()
        return p instanceof C03PacketPlayer
                || p instanceof C02PacketUseEntity
                || p instanceof C0APacketAnimation
                || p instanceof C0BPacketEntityAction
                || p instanceof C08PacketPlayerBlockPlacement
                || p instanceof C07PacketPlayerDigging
                || p instanceof C09PacketHeldItemChange;
    }
}
