package gregblockutils.Machines;

import codechicken.lib.render.CCRenderState;
import codechicken.lib.render.pipeline.ColourMultiplier;
import codechicken.lib.render.pipeline.IVertexOperation;
import codechicken.lib.vec.Cuboid6;
import codechicken.lib.vec.Matrix4;
import gregtech.api.capability.impl.FilteredFluidHandler;
import gregtech.api.capability.impl.FluidTankList;
import gregtech.api.gui.GuiTextures;
import gregtech.api.gui.ModularUI;
import gregtech.api.gui.widgets.FluidContainerSlotWidget;
import gregtech.api.gui.widgets.ImageWidget;
import gregtech.api.gui.widgets.SlotWidget;
import gregtech.api.gui.widgets.TankWidget;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntityHolder;
import gregtech.api.recipes.ModHandler;
import gregtech.api.render.SimpleSidedCubeRenderer;
import gregtech.api.render.Textures;
import gregtech.api.util.GTUtility;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.*;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.ItemStackHandler;
import org.apache.commons.lang3.ArrayUtils;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public class SteamPump extends MetaTileEntity {

    private static final Cuboid6 PIPE_CUBOID = new Cuboid6(4 / 16.0, 0.0, 4 / 16.0, 12 / 16.0, 1.0, 12 / 16.0);
    private static final int MAX_PUMP_RANGE = 16;
    private static final int PUMP_SPEED_BASE = 120;
    private static final int STEAM_DRAIN_PER_CYCLE = 250;

    private Deque<BlockPos> fluidSourceBlocks = new ArrayDeque<>();
    private Deque<BlockPos> blocksToCheck = new ArrayDeque<>();
    private boolean initializedQueue = false;
    private int pumpHeadY;
    protected FluidTank steamFluidTank;

    public SteamPump(String metaTileEntityId) {
        super(metaTileEntityId);
    }

    @Override
    public MetaTileEntity createMetaTileEntity(MetaTileEntityHolder holder) {
        return new SteamPump(metaTileEntityId);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void renderMetaTileEntity(CCRenderState renderState, Matrix4 translation, IVertexOperation[] pipeline) {
        IVertexOperation[] colouredPipeline = (IVertexOperation[])ArrayUtils.add(pipeline, new ColourMultiplier(GTUtility.convertRGBtoOpaqueRGBA_CL(this.getPaintingColorForRendering())));
        Textures.STEAM_CASING_BRONZE.render(renderState, translation, colouredPipeline);
        for (EnumFacing renderSide : EnumFacing.HORIZONTALS) {
            if (renderSide == getFrontFacing()) {
                Textures.PIPE_OUT_OVERLAY.renderSided(renderSide, renderState, translation, pipeline);
            } else {
                Textures.ADV_PUMP_OVERLAY.renderSided(renderSide, renderState, translation, pipeline);
            }
        }
        Textures.SCREEN.renderSided(EnumFacing.UP, renderState, translation, pipeline);
        Textures.PIPE_IN_OVERLAY.renderSided(EnumFacing.DOWN, renderState, translation, pipeline);
        for (int i = 0; i < pumpHeadY; i++) {
            translation.translate(0.0, -1.0, 0.0);
            Textures.STEAM_CASING_BRONZE.render(renderState, translation, pipeline, PIPE_CUBOID);
        }
    }

    @Override
    public void writeInitialSyncData(PacketBuffer buf) {
        super.writeInitialSyncData(buf);
        buf.writeInt(pumpHeadY);
    }

    @Override
    public void receiveInitialSyncData(PacketBuffer buf) {
        super.receiveInitialSyncData(buf);
        this.pumpHeadY = buf.readInt();
    }

    @Override
    public void receiveCustomData(int dataId, PacketBuffer buf) {
        super.receiveCustomData(dataId, buf);
        if (dataId == -200) {
            this.pumpHeadY = buf.readInt();
        }
    }

    @Override
    protected FluidTankList createExportFluidHandler() {
        return new FluidTankList(new FluidTank(4000));
    }

    public int getSteamCapacity() {
        return 4000;
    }

    public FluidTankList createImportFluidHandler() {
        this.steamFluidTank = (new FilteredFluidHandler(this.getSteamCapacity())).setFillPredicate(ModHandler::isSteam);
        return new FluidTankList(new IFluidTank[]{this.steamFluidTank});
    }

    @Override
    protected IItemHandlerModifiable createImportItemHandler() {
        return new ItemStackHandler(1);
    }

    @Override
    protected IItemHandlerModifiable createExportItemHandler() {
        return new ItemStackHandler(1);
    }

    @Override
    public boolean hasCapability(Capability<?> capability, EnumFacing side) {
        return (side == null || side.getAxis() != EnumFacing.Axis.Y) && super.hasCapability(capability, side);
    }

    @Override
    public <T> T getCapability(Capability<T> capability, EnumFacing side) {
        return (side == null || side.getAxis() == EnumFacing.Axis.Y) ? null : super.getCapability(capability, side);
    }

    @Override
    protected ModularUI createUI(EntityPlayer entityPlayer) {
        ModularUI.Builder builder = ModularUI.defaultBuilder();
        builder.image(7, 16, 81, 55, GuiTextures.DISPLAY);
        TankWidget tankWidget = new TankWidget(exportFluids.getTankAt(0), 69, 52, 18, 18)
                .setHideTooltip(true).setAlwaysShowFull(true);
        builder.widget(tankWidget);
        builder.label(11, 20, "gregtech.gui.fluid_amount", 0xFFFFFF);
        builder.dynamicLabel(11, 30, tankWidget::getFormattedFluidAmount, 0xFFFFFF);
        builder.dynamicLabel(11, 40, tankWidget::getFluidLocalizedName, 0xFFFFFF);
        return builder.label(6, 6, getMetaFullName())
                .widget(new FluidContainerSlotWidget(importItems, 0, 90, 17)
                        .setBackgroundTexture(GuiTextures.SLOT, GuiTextures.IN_SLOT_OVERLAY))
                .widget(new ImageWidget(91, 36, 14, 15, GuiTextures.TANK_ICON))
                .widget(new SlotWidget(exportItems, 0, 90, 54, true, false)
                        .setBackgroundTexture(GuiTextures.SLOT, GuiTextures.OUT_SLOT_OVERLAY))
                .bindPlayerInventory(entityPlayer.inventory)
                .build(getHolder(), entityPlayer);
    }

    private boolean isStraightInPumpRange(BlockPos checkPos) {
        BlockPos pos = getPos();
        return checkPos.getX() == pos.getX() &&
                checkPos.getZ() == pos.getZ() &&
                pos.getY() < checkPos.getY() &&
                pos.getY() + pumpHeadY >= checkPos.getY();
    }

    private void updateQueueState() {
        BlockPos selfPos = getPos().down(pumpHeadY);
        if (!blocksToCheck.isEmpty()) {
            BlockPos checkPos = this.blocksToCheck.poll();
            IBlockState blockHere = getWorld().getBlockState(checkPos);
            boolean shouldCheckNeighbours = isStraightInPumpRange(checkPos);
            if (blockHere.getBlock() instanceof BlockLiquid ||
                    blockHere.getBlock() instanceof IFluidBlock) {
                IFluidHandler fluidHandler = FluidUtil.getFluidHandler(getWorld(), checkPos, null);
                FluidStack drainStack = fluidHandler.drain(Integer.MAX_VALUE, false);
                if (drainStack != null && drainStack.amount > 0) {
                    this.fluidSourceBlocks.add(checkPos);
                }
                shouldCheckNeighbours = true;
            }

            if (shouldCheckNeighbours) {
                for (EnumFacing facing : EnumFacing.VALUES) {
                    BlockPos offsetPos = checkPos.offset(facing);
                    if (offsetPos.distanceSq(selfPos) > MAX_PUMP_RANGE * MAX_PUMP_RANGE)
                        continue; //do not add blocks outside bounds
                    this.blocksToCheck.add(offsetPos);
                }
            }

        } else if (fluidSourceBlocks.isEmpty()) {
            if (getTimer() % 20 == 0 && pumpHeadY < 50) {
                this.pumpHeadY++;
                writeCustomData(-200, b -> b.writeInt(pumpHeadY));
                markDirty();
                //schedule queue rebuild because we changed our position and no fluid is available
                this.initializedQueue = false;
            }

            if (!initializedQueue || getTimer() % 6000 == 0) {
                this.initializedQueue = true;
                //just add ourselves to check list and see how this will go
                this.blocksToCheck.add(selfPos);
            }
        }
    }

    private void tryPumpFirstBlock() {
        BlockPos fluidBlockPos = fluidSourceBlocks.poll();
        if (fluidBlockPos == null) return;
        IBlockState blockHere = getWorld().getBlockState(fluidBlockPos);
        if (blockHere.getBlock() instanceof BlockLiquid ||
                blockHere.getBlock() instanceof IFluidBlock) {
            IFluidHandler fluidHandler = FluidUtil.getFluidHandler(getWorld(), fluidBlockPos, null);
            FluidStack drainStack = fluidHandler.drain(Integer.MAX_VALUE, false);
            if (drainStack != null && exportFluids.fill(drainStack, false) == drainStack.amount) {
                exportFluids.fill(drainStack, true);
                fluidHandler.drain(drainStack.amount, true);
                this.fluidSourceBlocks.remove(fluidBlockPos);
                steamFluidTank.drain(STEAM_DRAIN_PER_CYCLE, true);
            }
        }
    }

    @Override
    public void update() {
        super.update();
        if (getWorld().isRemote) {
            return;
        }
        //do not do anything without enough energy supplied
        if (steamFluidTank.getFluidAmount() < STEAM_DRAIN_PER_CYCLE * 4) {
            return;
        }
        pushFluidsIntoNearbyHandlers(getFrontFacing());
        fillContainerFromInternalTank(importItems, exportItems, 0, 0);
        updateQueueState();
        if (getTimer() % getPumpingCycleLength() == 0 && !fluidSourceBlocks.isEmpty() &&
                steamFluidTank.getFluidAmount() >= STEAM_DRAIN_PER_CYCLE) {
            tryPumpFirstBlock();
        }
    }

    private int getPumpingCycleLength() {
        return PUMP_SPEED_BASE;
    }

    @Override
    public void addInformation(ItemStack stack, @Nullable World player, List<String> tooltip, boolean advanced) {
        tooltip.add(I18n.format("gregtech.machine.pump.tooltip_range", MAX_PUMP_RANGE, MAX_PUMP_RANGE));
        tooltip.add(I18n.format("gregtech.machine.pump.tooltip_speed", getPumpingCycleLength()));
        tooltip.add(I18n.format("gregtech.universal.tooltip.fluid_storage_capacity", exportFluids.getTankAt(0).getCapacity()));
    }
}