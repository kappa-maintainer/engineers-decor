/*
 * @file EdMilker.java
 * @author Stefan Wilhelm (wile)
 * @copyright (C) 2020 Stefan Wilhelm
 * @license MIT (see https://opensource.org/licenses/MIT)
 *
 * Frequently attracts and milks nearby cows
 */
package wile.engineersdecor.blocks;

import net.minecraft.world.World;
import net.minecraft.world.IWorldReader;
import net.minecraft.world.IBlockReader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.state.BooleanProperty;
import net.minecraft.state.StateContainer;
import net.minecraft.tileentity.ITickableTileEntity;
import net.minecraft.tileentity.TileEntityType;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.entity.CreatureEntity;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.*;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.Fluids;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.*;
import net.minecraft.util.math.*;
import net.minecraft.util.math.vector.*;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.PlayerMainInvWrapper;
import net.minecraftforge.fluids.IFluidTank;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraftforge.registries.ForgeRegistries;
import wile.engineersdecor.ModConfig;
import wile.engineersdecor.libmc.detail.*;
import wile.engineersdecor.ModContent;
import wile.engineersdecor.detail.ExternalObjects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;


public class EdMilker
{
  public static void on_config(int energy_consumption_per_tick, int min_milking_delay_per_cow)
  { MilkerTileEntity.on_config(energy_consumption_per_tick, min_milking_delay_per_cow); }

  //--------------------------------------------------------------------------------------------------------------------
  // Block
  //--------------------------------------------------------------------------------------------------------------------

  public static class MilkerBlock extends DecorBlock.Horizontal implements IDecorBlock
  {
    public static final BooleanProperty FILLED = BooleanProperty.create("filled");
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    public MilkerBlock(long config, Block.Properties builder, final AxisAlignedBB[] unrotatedAABBs)
    { super(config, builder, unrotatedAABBs); }

    @Override
    protected void fillStateContainer(StateContainer.Builder<Block, BlockState> builder)
    { super.fillStateContainer(builder); builder.add(ACTIVE); builder.add(FILLED); }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockItemUseContext context)
    { return super.getStateForPlacement(context).with(FILLED, false).with(ACTIVE, false); }

    @Override
    @SuppressWarnings("deprecation")
    public boolean hasComparatorInputOverride(BlockState state)
    { return true; }

    @Override
    @SuppressWarnings("deprecation")
    public int getComparatorInputOverride(BlockState state, World world, BlockPos pos)
    {
      MilkerTileEntity te = getTe(world, pos);
      return (te==null) ? 0 : MathHelper.clamp((16 * te.fluid_level())/MilkerTileEntity.TANK_CAPACITY, 0, 15);
    }

    @Override
    public boolean shouldCheckWeakPower(BlockState state, IWorldReader world, BlockPos pos, Direction side)
    { return false; }

    @Override
    public boolean hasTileEntity(BlockState state)
    { return true; }

    @Override
    @Nullable
    public TileEntity createTileEntity(BlockState state, IBlockReader world)
    { return new MilkerTileEntity(); }

    @Override
    public ActionResultType onBlockActivated(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockRayTraceResult hit)
    {
      if(world.isRemote()) return ActionResultType.SUCCESS;
      MilkerTileEntity te = getTe(world, pos);
      if(te==null) return ActionResultType.FAIL;
      final ItemStack in_stack = player.getHeldItem(hand);
      final ItemStack out_stack = MilkerTileEntity.milk_filled_container_item(in_stack);
      if(in_stack.isEmpty()) {
        te.state_message(player);
        return ActionResultType.CONSUME;
      } else if(out_stack.isEmpty() && (te.fluid_handler()!=null)) {
        return FluidUtil.interactWithFluidHandler(player, hand, te.fluid_handler()) ? ActionResultType.CONSUME : ActionResultType.FAIL;
      } else {
        boolean drained = false;
        IItemHandler player_inventory = new PlayerMainInvWrapper(player.inventory);
        if(te.fluid_level() >= MilkerTileEntity.BUCKET_SIZE) {
          final ItemStack insert_stack = out_stack.copy();
          ItemStack remainder = ItemHandlerHelper.insertItemStacked(player_inventory, insert_stack, false);
          if(remainder.getCount() < insert_stack.getCount()) {
            te.drain(MilkerTileEntity.BUCKET_SIZE);
            in_stack.shrink(1);
            drained = true;
            if(remainder.getCount() > 0) {
              final ItemEntity ei = new ItemEntity(world, player.getPositionVec().getX(), player.getPositionVec().getY()+0.5, player.getPositionVec().getZ(), remainder);
              ei.setPickupDelay(40);
              ei.setMotion(0,0,0);
              world.addEntity(ei);
            }
          }
        }
        if(drained) {
          world.playSound(null, pos, SoundEvents.ITEM_BUCKET_FILL, SoundCategory.BLOCKS, 0.8f, 1f);
        }
      }
      return ActionResultType.CONSUME;
    }

    @Nullable
    private MilkerTileEntity getTe(World world, BlockPos pos)
    { final TileEntity te=world.getTileEntity(pos); return (!(te instanceof MilkerTileEntity)) ? (null) : ((MilkerTileEntity)te); }
  }

  //--------------------------------------------------------------------------------------------------------------------
  // Tile entity
  //--------------------------------------------------------------------------------------------------------------------

  public static class MilkerTileEntity extends TileEntity implements ITickableTileEntity, IFluidTank, ICapabilityProvider
  {
    public static final int BUCKET_SIZE = 1000;
    public static final int TICK_INTERVAL = 80;
    public static final int PROCESSING_TICK_INTERVAL = 20;
    public static final int TANK_CAPACITY = BUCKET_SIZE * 12;
    public static final int MAX_MILKING_TANK_LEVEL = TANK_CAPACITY-500;
    public static final int FILLED_INDICATION_THRESHOLD = BUCKET_SIZE;
    public static final int MAX_ENERGY_BUFFER = 16000;
    public static final int MAX_ENERGY_TRANSFER = 512;
    public static final int DEFAULT_ENERGY_CONSUMPTION = 0;
    public static final int DEFAULT_MILKING_DELAY_PER_COW = 4000;
    private static final FluidStack NO_MILK_FLUID = new FluidStack(Fluids.WATER, 0);
    private static final Direction FLUID_TRANSFER_DIRECTRIONS[] = {Direction.DOWN,Direction.EAST,Direction.SOUTH,Direction.WEST,Direction.NORTH};
    private enum MilkingState { IDLE, PICKED, COMING, POSITIONING, MILKING, LEAVING, WAITING }

    private static FluidStack milk_fluid_ = NO_MILK_FLUID;
    private static HashMap<ItemStack, ItemStack> milk_containers_ = new HashMap<>();
    private static int energy_consumption_ = DEFAULT_ENERGY_CONSUMPTION;
    private static long min_milking_delay_per_cow_ticks_ = DEFAULT_MILKING_DELAY_PER_COW;
    private int tick_timer_;
    private UUID tracked_cow_ = null;
    private MilkingState state_ = MilkingState.IDLE;
    private int state_timeout_ = 0;
    private int state_timer_ = 0;
    private BlockPos tracked_cow_original_position_ = null;
    private final RfEnergy.Battery battery_;
    private final LazyOptional<IEnergyStorage> energy_handler_;
    private final Fluidics.Tank tank_;
    private final LazyOptional<IFluidHandler> fluid_handler_;

    public static void on_config(int energy_consumption_per_tick, int min_milking_delay_per_cow)
    {
      energy_consumption_ = MathHelper.clamp(energy_consumption_per_tick, 0, 1024);
      min_milking_delay_per_cow_ticks_ = MathHelper.clamp(min_milking_delay_per_cow, 1000, 24000);
      {
        ResourceLocation milk_rl = ForgeRegistries.FLUIDS.getKeys().stream().filter(rl->rl.getPath().equals("milk")).findFirst().orElse(null);
        if(milk_rl != null) {
          Fluid milk = ForgeRegistries.FLUIDS.getValue(milk_rl);
          if(milk != null) milk_fluid_ = new FluidStack(milk, BUCKET_SIZE);
        }
      }
      {
        milk_containers_.put(new ItemStack(Items.BUCKET), new ItemStack(Items.MILK_BUCKET));
        if(ExternalObjects.BOTTLED_MILK_BOTTLE_DRINKLABLE!=null) milk_containers_.put(new ItemStack(Items.GLASS_BOTTLE), new ItemStack(ExternalObjects.BOTTLED_MILK_BOTTLE_DRINKLABLE));
      }
      ModConfig.log(
        "Config milker: energy consumption:" + energy_consumption_ + "rf/t"
          + ((milk_fluid_==NO_MILK_FLUID)?"[no milk fluid registered]":" [milk fluid available]")
          + ((ExternalObjects.BOTTLED_MILK_BOTTLE_DRINKLABLE==null)?"":" [bottledmilk mod available]")
      );
    }

    public MilkerTileEntity()
    { this(ModContent.TET_SMALL_MILKING_MACHINE); }

    public MilkerTileEntity(TileEntityType<?> te_type)
    {
      super(te_type);
      tank_ = new Fluidics.Tank(TANK_CAPACITY, 0, BUCKET_SIZE, (fs)->(has_milk_fluid() && fs.isFluidEqual(milk_fluid_)));
      fluid_handler_ = tank_.createFluidHandler();
      battery_ = new RfEnergy.Battery(MAX_ENERGY_BUFFER, MAX_ENERGY_TRANSFER, 0);
      energy_handler_ = battery_.createEnergyHandler();
      reset();
    }

    public void reset()
    {
      tank_.clear();
      battery_.clear();
      tick_timer_ = 0;
      tracked_cow_ = null;
      state_ = MilkingState.IDLE;
      state_timeout_ = 0;
    }

    public CompoundNBT destroy_getnbt()
    {
      final UUID cowuid = tracked_cow_;
      CompoundNBT nbt = new CompoundNBT();
      writenbt(nbt, false); reset();
      if(cowuid == null) return nbt;
      world.getEntitiesWithinAABB(CowEntity.class, new AxisAlignedBB(pos).grow(16, 16, 16), e->e.getUniqueID().equals(cowuid)).forEach(e->e.setNoAI(false));
      return nbt;
    }

    public void readnbt(CompoundNBT nbt, boolean update_packet)
    {
      battery_.load(nbt);
      tank_.load(nbt);
    }

    protected void writenbt(CompoundNBT nbt, boolean update_packet)
    {
      tank_.save(nbt);
      if(!battery_.isEmpty()) battery_.save(nbt);
    }

    private IFluidHandler fluid_handler()
    { return fluid_handler_.orElse(null); }

    private int fluid_level()
    { return tank_.getFluidAmount(); }

    private FluidStack drain(int amount)
    { return tank_.drain(amount); }

    public void state_message(PlayerEntity player)
    {
      ITextComponent rf = (energy_consumption_ <= 0) ? (new StringTextComponent("")) : (Auxiliaries.localizable("block.engineersdecor.small_milking_machine.status.rf", new Object[]{battery_.getEnergyStored()}));
      Overlay.show(player, Auxiliaries.localizable("block.engineersdecor.small_milking_machine.status", new Object[]{tank_.getFluidAmount(), rf}));
    }

    // TileEntity ------------------------------------------------------------------------------

    @Override
    public void read(BlockState state, CompoundNBT nbt)
    { super.read(state, nbt); readnbt(nbt, false); }

    @Override
    public CompoundNBT write(CompoundNBT nbt)
    { super.write(nbt); writenbt(nbt, false); return nbt; }

    @Override
    public void remove()
    {
      super.remove();
      energy_handler_.invalidate();
      fluid_handler_.invalidate();
    }

    // IFluidTank ------------------------------------------------------------------------------------------

    private boolean has_milk_fluid()
    { return !(NO_MILK_FLUID.isFluidEqual(milk_fluid_)); }

    @Override
    @Nonnull
    public FluidStack getFluid()
    { return has_milk_fluid() ? (new FluidStack(milk_fluid_, fluid_level())) : (FluidStack.EMPTY); }

    @Override
    public int getFluidAmount()
    { return has_milk_fluid() ? fluid_level() : 0; }

    @Override
    public int getCapacity()
    { return TANK_CAPACITY; }

    @Override
    public boolean isFluidValid(FluidStack stack)
    { return has_milk_fluid() && stack.isFluidEqual(milk_fluid_); }

    @Override
    public int fill(FluidStack resource, FluidAction action)
    { return 0; }

    @Override
    @Nonnull
    public FluidStack drain(FluidStack resource, FluidAction action)
    { return (!resource.isFluidEqual(milk_fluid_)) ? (FluidStack.EMPTY) : drain(resource.getAmount(), action); }

    @Override
    @Nonnull
    public FluidStack drain(int maxDrain, FluidAction action)
    {
      if((!has_milk_fluid()) || (fluid_level() <= 0)) return FluidStack.EMPTY;
      return tank_.drain(maxDrain, action);
    }

    // ICapabilityProvider ---------------------------------------------------------------------------

    @Override
    public <T> LazyOptional<T> getCapability(net.minecraftforge.common.capabilities.Capability<T> capability, @Nullable Direction facing)
    {
      if((capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) && has_milk_fluid()) return fluid_handler_.cast();
      if((capability == CapabilityEnergy.ENERGY) && (energy_consumption_>0)) return energy_handler_.cast();
      return super.getCapability(capability, facing);
    }

    // ITickable ------------------------------------------------------------------------------------

    private static final HashMap<Integer, Long> tracked_cows_ = new HashMap<Integer, Long>();

    private void log(String s)
    {} // println("Milker|" + s); may be enabled with config, for dev was println

    private static ItemStack milk_filled_container_item(ItemStack stack)
    { return milk_containers_.entrySet().stream().filter(e->Inventories.areItemStacksIdentical(e.getKey(), stack)).map(Map.Entry::getValue).findFirst().orElse(ItemStack.EMPTY); }

    private boolean fill_adjacent_inventory_item_containers(Direction block_facing)
    {
      // Check inventory existence, back to down is preferred, otherwise sort back into same inventory.
      IItemHandler src = Inventories.itemhandler(world, pos.offset(block_facing), block_facing.getOpposite());
      IItemHandler dst = Inventories.itemhandler(world, pos.down(), Direction.UP);
      if(src==null) { src = dst; } else if(dst==null) { dst = src; }
      if((src==null) || (dst==null)) return false;
      boolean dirty = false;
      while((tank_.getFluidAmount() >= BUCKET_SIZE)) {
        boolean inserted = false;
        for(Entry<ItemStack,ItemStack> e:milk_containers_.entrySet()) {
          if(Inventories.extract(src, e.getKey(), 1, true).isEmpty()) continue;
          if(!Inventories.insert(dst, e.getValue().copy(), false).isEmpty()) continue;
          Inventories.extract(src, e.getKey(), 1, false);
          tank_.drain(BUCKET_SIZE);
          inserted = true;
          dirty = true;
          break;
        }
        if(!inserted) break;
      }
      return dirty;
    }

    private boolean fill_adjacent_tank()
    {
      if((fluid_level()<=0) || (!has_milk_fluid())) return false;
      final FluidStack fs = new FluidStack(milk_fluid_, Math.max(fluid_level(), BUCKET_SIZE));
      for(Direction dir:Direction.values()) {
        int amount = Fluidics.fill(getWorld(), getPos().offset(dir), dir.getOpposite(), fs);
        if(amount > 0) {
          tank_.drain(amount);
          return true;
        }
      }
      return false;
    }

    private void release_cow(CowEntity cow)
    {
      log("release cow");
      if(cow != null) {
        cow.setNoAI(false);
        SingleMoveGoal.abortFor(cow);
        tracked_cows_.remove(cow.getEntityId());
        for(int id:tracked_cows_.keySet().stream().filter(i->cow.getEntityWorld().getEntityByID(i)==null).collect(Collectors.toList())) {
          tracked_cows_.remove(id);
        }
      }
      tracked_cow_ = null;
      state_ = MilkingState.IDLE;
      tick_timer_ = TICK_INTERVAL;
    }

    private boolean milking_process()
    {
      if((tracked_cow_ == null) && (fluid_level() >= MAX_MILKING_TANK_LEVEL)) return false; // nothing to do
      final Direction facing = world.getBlockState(getPos()).get(MilkerBlock.HORIZONTAL_FACING).getOpposite();
      final Vector3d target_pos = Vector3d.copy(getPos().offset(facing)).add(0.5,0,0.5);
      CowEntity cow = null;
      {
        AxisAlignedBB aabb = new AxisAlignedBB(pos.offset(facing, 3)).grow(4, 2, 4);
        final long t = world.getGameTime();
        final List<CowEntity> cows = world.getEntitiesWithinAABB(CowEntity.class, aabb,
          e-> {
            if(e.getUniqueID().equals(tracked_cow_)) return true;
            if((tracked_cow_!=null) || e.isChild() || e.isInLove() || e.isBeingRidden()) return false;
            if(!e.getNavigator().noPath()) return false;
            if(Math.abs(tracked_cows_.getOrDefault(e.getEntityId(), 0L)-t) < min_milking_delay_per_cow_ticks_) return false;
            return true;
          }
        );
        if(cows.size() == 1) {
          cow = cows.get(0); // tracked or only one
        } else if(cows.size() > 1) {
          cow = cows.get(world.rand.nextInt(cows.size()-1)); // pick one
        }
      }
      if((state_ != MilkingState.IDLE) && ((state_timeout_ -= PROCESSING_TICK_INTERVAL) <= 0)) { release_cow(cow); log("Cow motion timeout"); cow = null; }
      if((cow == null) || (!cow.isAlive())) { release_cow(cow); cow = null; }
      if(tracked_cow_ == null) state_ = MilkingState.IDLE;
      if(cow == null) { log("Init: No cow"); return false; } // retry next cycle
      tick_timer_ = PROCESSING_TICK_INTERVAL;
      state_timer_ -= PROCESSING_TICK_INTERVAL;
      if(state_timer_ > 0) return false;
      switch(state_) { // Let's do this the old school FSA sequencing way ...
        case IDLE: {
          final List<LivingEntity> blocking_entities = world.getEntitiesWithinAABB(LivingEntity.class, new AxisAlignedBB(pos.offset(facing)).grow(0.5, 0.5, 0.5));
          if(blocking_entities.size() > 0) {
            tick_timer_ = TICK_INTERVAL;
            log("Idle: Position blocked");
            if(blocking_entities.get(0) instanceof CowEntity) {
              CowEntity blocker = (CowEntity)blocking_entities.get(0);
              BlockPos p = getPos().offset(facing,2);
              log("Idle: Shove off");
              blocker.setNoAI(false);
              SingleMoveGoal.startFor(blocker, p, 2, 1.0, (goal, world, pos)->(pos.distanceSq(goal.getCreature().getPosition())>100));
            }
            return false;
          }
          if(cow.getLeashed() || cow.isChild() || cow.isInLove() || (!cow.isOnGround()) || cow.isBeingRidden() || cow.isSprinting()) return false;
          tracked_cows_.put(cow.getEntityId(), cow.getEntityWorld().getGameTime());
          tracked_cow_ = cow.getUniqueID();
          state_ = MilkingState.PICKED;
          state_timeout_ = 200;
          tracked_cow_original_position_ = cow.getPosition();
          log("Idle: Picked cow " + tracked_cow_);
          return false;
        }
        case PICKED: {
          SingleMoveGoal.startFor(
            cow, target_pos, 2, 1.0,
            (goal, world, pos)->(pos.distanceSq(goal.getCreature().getPosition())>100),
            (goal, world, pos)->{
              log("move: position reached");
              goal.getCreature().setLocationAndAngles(goal.getTargetPosition().getX(), goal.getTargetPosition().getY(), goal.getTargetPosition().getZ(), facing.getHorizontalAngle(), 0);
            },
            (goal, world, pos)->{
              log("move: aborted");
            }
          );
          state_ = MilkingState.COMING;
          state_timeout_ = 400; // 15s should be enough
          log("Picked: coming to " + target_pos);
          return false;
        }
        case COMING: {
          if(target_pos.squareDistanceTo(cow.getPositionVec()) <= 1) {
            log("Coming: position reached");
            state_ = MilkingState.POSITIONING;
            state_timeout_ = 100; // 5s
          } else if((!SingleMoveGoal.isActiveFor(cow))) {
            release_cow(cow);
            log("Coming: aborted");
          } else {
            state_timeout_ -= 100;
          }
          return false;
        }
        case POSITIONING: {
          log("Positioning: start milking");
          SingleMoveGoal.abortFor(cow);
          cow.setNoAI(true);
          cow.setLocationAndAngles(target_pos.getX(), target_pos.getY(), target_pos.getZ(), facing.getHorizontalAngle(), 0);
          world.playSound(null, pos, SoundEvents.ENTITY_COW_MILK, SoundCategory.BLOCKS, 0.5f, 1f);
          state_timeout_ = 600;
          state_ = MilkingState.MILKING;
          state_timer_ = 30;
          return false;
        }
        case MILKING: {
          tank_.fill(milk_fluid_.copy(), FluidAction.EXECUTE);
          state_timeout_ = 600;
          state_ = MilkingState.LEAVING;
          state_timer_ = 20;
          cow.setNoAI(false);
          cow.getNavigator().clearPath();
          log("Milking: done, leave");
          return true;
        }
        case LEAVING: {
          BlockPos p = (tracked_cow_original_position_ != null) ? (tracked_cow_original_position_) : getPos().offset(facing,2).offset(facing.rotateYCCW());
          SingleMoveGoal.startFor(cow, p, 2, 1.0, (goal, world, pos)->(pos.distanceSq(goal.getCreature().getPosition())>100));
          state_timeout_ = 600;
          state_timer_ = 500;
          tick_timer_ = TICK_INTERVAL;
          state_ = MilkingState.WAITING;
          tracked_cows_.put(cow.getEntityId(), cow.getEntityWorld().getGameTime());
          log("Leaving: process done");
          return true;
        }
        case WAITING: {
          // wait for the timeout to kick in until starting with the next.
          tick_timer_ = TICK_INTERVAL;
          if(state_timer_ < 40) {
            tracked_cow_ = null;
            release_cow(null);
          }
          log("Waiting time elapsed");
          return true;
        }
        default: {
          release_cow(cow);
        }
      }
      return (tracked_cow_ != null);
    }

    @Override
    public void tick()
    {
      if((world.isRemote) || ((--tick_timer_ > 0))) return;
      tick_timer_ = TICK_INTERVAL;
      boolean dirty = false;
      final BlockState block_state = world.getBlockState(pos);
      if(!(block_state.getBlock() instanceof MilkerBlock)) return;
      if(!world.isBlockPowered(pos) || (state_ != MilkingState.IDLE)) {
        if((energy_consumption_ > 0) && (!battery_.draw(energy_consumption_))) return;
        // Track and milk cows
        if(milking_process()) dirty = true;
        // Fluid transfer
        if(has_milk_fluid() && (!tank_.isEmpty())) {
          log("Fluid transfer");
          for(Direction facing: FLUID_TRANSFER_DIRECTRIONS) {
            final IFluidHandler fh = FluidUtil.getFluidHandler(world, pos.offset(facing), facing.getOpposite()).orElse(null);
            if(fh == null) continue;
            final FluidStack fs = tank_.drain(BUCKET_SIZE, FluidAction.SIMULATE);
            int nfilled = fh.fill(fs, FluidAction.EXECUTE);
            if(nfilled <= 0) continue;
            tank_.drain(nfilled, FluidAction.EXECUTE);
            dirty = true;
            break;
          }
        }
        // Adjacent inventory update, only done just after milking to prevent waste of server cpu.
        if((!dirty) && (fluid_level() > 0)) {
          log("Try item transfer");
          if(fill_adjacent_tank() || ((fluid_level() >= BUCKET_SIZE) && fill_adjacent_inventory_item_containers(block_state.get(MilkerBlock.HORIZONTAL_FACING)))) dirty = true;
        }
      }
      // State update
      BlockState new_state = block_state.with(MilkerBlock.FILLED, fluid_level()>=FILLED_INDICATION_THRESHOLD).with(MilkerBlock.ACTIVE, state_==MilkingState.MILKING);
      if(block_state != new_state) world.setBlockState(pos, new_state,1|2|16);
      if(dirty) markDirty();
    }
  }

  public static class SingleMoveGoal extends net.minecraft.entity.ai.goal.MoveToBlockGoal
  {
    @FunctionalInterface public interface TargetPositionInValidCheck { boolean test(SingleMoveGoal goal, IWorldReader world, BlockPos pos); }
    @FunctionalInterface public interface StrollEvent { void apply(SingleMoveGoal goal, IWorldReader world, Vector3d pos); }
    private static void log(String s) {} // println("SingleMoveGoal: "+s);

    private static final HashMap<Integer, SingleMoveGoal> tracked_entities_ = new HashMap<Integer, SingleMoveGoal>();
    private static final int motion_timeout = 20*20;
    private boolean aborted_;
    private boolean in_position_;
    private boolean was_aborted_;
    private Vector3d target_pos_;
    private TargetPositionInValidCheck abort_condition_;
    private StrollEvent on_target_position_reached_;
    private StrollEvent on_aborted_;

    public SingleMoveGoal(CreatureEntity creature, Vector3d pos, double speed, TargetPositionInValidCheck abort_condition, @Nullable StrollEvent on_position_reached, @Nullable StrollEvent on_aborted)
    {
      super(creature, speed, 32, 32);
      abort_condition_ = abort_condition;
      on_target_position_reached_ = on_position_reached;
      on_aborted_ = on_aborted;
      destinationBlock = new BlockPos(pos.getX(), pos.getY(), pos.getZ());
      timeoutCounter = 0;
      runDelay = 0;
      aborted_ = false;
      was_aborted_ = false;
      target_pos_ = pos;
    }

    public static void startFor(CreatureEntity entity, BlockPos target_pos, int priority, double speed, TargetPositionInValidCheck abort_condition)
    { startFor(entity, new Vector3d(target_pos.getX(),target_pos.getY(),target_pos.getZ()), priority, speed, abort_condition, null, null); }

    public static boolean startFor(CreatureEntity entity, Vector3d target_pos, int priority, double speed, TargetPositionInValidCheck abort_condition, @Nullable StrollEvent on_position_reached, @Nullable StrollEvent on_aborted)
    {
      synchronized(tracked_entities_) {
        SingleMoveGoal goal = tracked_entities_.getOrDefault(entity.getEntityId(), null);
        if(goal != null) {
          if(!goal.aborted()) return false; // that is still running.
          entity.goalSelector.removeGoal(goal);
        }
        log("::start("+entity.getEntityId()+")");
        goal = new SingleMoveGoal(entity, target_pos, speed, abort_condition, on_position_reached, on_aborted);
        tracked_entities_.put(entity.getEntityId(), goal);
        entity.goalSelector.addGoal(priority, goal);
        return true;
      }
    }

    public static boolean isActiveFor(CreatureEntity entity)
    { return (entity != null) && (entity.goalSelector.getRunningGoals().anyMatch(
      g->((g.getGoal()) instanceof SingleMoveGoal) && (!((SingleMoveGoal)(g.getGoal())).aborted())
    )); }

    public static void abortFor(CreatureEntity entity)
    {
      log("::abort("+entity.getEntityId()+")");
      if(entity.isAlive()) {
        entity.goalSelector.getRunningGoals().filter(g->(g.getGoal()) instanceof SingleMoveGoal).forEach(g->((SingleMoveGoal)g.getGoal()).abort());
      }
      final World world = entity.getEntityWorld();
      if(world != null) {
        // @todo: check nicer way to filter a map.
        List<Integer> to_remove = tracked_entities_.keySet().stream().filter(i->(world.getEntityByID(i) == null)).collect(Collectors.toList());
        for(int id:to_remove)tracked_entities_.remove(id);
      }
    }

    public Vector3d getTargetPosition()
    { return target_pos_; }

    public CreatureEntity getCreature()
    { return creature; }

    public synchronized void abort()
    { aborted_ = true; }

    public synchronized boolean aborted()
    { return aborted_; }

    public synchronized void initialize(Vector3d target_pos, double speed, TargetPositionInValidCheck abort_condition, @Nullable StrollEvent on_position_reached, @Nullable StrollEvent on_aborted)
    {
      abort_condition_ = abort_condition;
      on_target_position_reached_ = on_position_reached;
      on_aborted_ = on_aborted;
      destinationBlock = new BlockPos(target_pos.getX(), target_pos.getY(), target_pos.getZ());
      timeoutCounter = 0;
      runDelay = 0;
      aborted_ = false;
      was_aborted_ = false;
      target_pos_ = new Vector3d(target_pos.getX(), target_pos.getY(), target_pos.getZ());
      // this.movementSpeed = speed; -> that is final, need to override tick and func_whatever
    }

    @Override
    public void resetTask()
    { runDelay = 0; timeoutCounter = 0; }

    @Override
    public double getTargetDistanceSq()
    { return 0.7; }

    @Override
    public boolean shouldMove()
    { return (!aborted()) && (timeoutCounter & 0x7) == 0; }

    @Override
    public boolean shouldExecute()
    {
      if(aborted_) {
        if((!was_aborted_) && (on_aborted_!=null)) on_aborted_.apply(this, creature.world, target_pos_);
        was_aborted_ = true;
        return false;
      } else if(!shouldMoveTo(creature.world, destinationBlock)) {
        synchronized(this) { aborted_ = true; }
        return false;
      } else if(--runDelay > 0) {
        return false;
      } else {
        runDelay = 10;
        return true;
      }
    }

    @Override
    public void startExecuting()
    {
      timeoutCounter = 0;
      if(!creature.getNavigator().tryMoveToXYZ(target_pos_.getX(), target_pos_.getY(), target_pos_.getZ(), this.movementSpeed)) {
        abort();
        log("startExecuting() -> abort, no path");
      } else {
        log("startExecuting() -> started");
      }
    }

    public boolean shouldContinueExecuting()
    {
      if(aborted()) {
        log("shouldContinueExecuting() -> already aborted");
        return false;
      } else if(creature.getNavigator().noPath()) {
        if((!creature.getNavigator().setPath(creature.getNavigator().getPathToPos(target_pos_.getX(), target_pos_.getY(), target_pos_.getZ(), 0), movementSpeed))) {
          log("shouldContinueExecuting() -> abort, no path");
          abort();
          return false;
        } else {
          return true;
        }
      } else if(timeoutCounter > motion_timeout) {
        log("shouldContinueExecuting() -> abort, timeout");
        abort();
        return false;
      } else if(!shouldMoveTo(creature.world, destinationBlock)) {
        log("shouldContinueExecuting() -> abort, !shouldMoveTo()");
        abort();
        return false;
      } else {
        log("shouldContinueExecuting() -> yes");
        return true;
      }
    }

    @Override
    protected boolean shouldMoveTo(IWorldReader world, BlockPos pos)
    {
      if(abort_condition_.test(this, world, pos)) {
        log("shouldMoveTo() -> abort_condition");
        return false;
      } else {
        return true;
      }
    }

    @Override
    public void tick()
    {
      BlockPos testpos = new BlockPos(target_pos_.getX(), creature.getPositionVec().getY(), target_pos_.getZ());
      if(!testpos.withinDistance(creature.getPositionVec(), getTargetDistanceSq())) {
        if((++timeoutCounter > motion_timeout)) {
          log("tick() -> abort, timeoutCounter");
          abort();
          return;
        }
        if(shouldMove() && (!creature.getNavigator().tryMoveToXYZ(target_pos_.getX(), target_pos_.getY(), target_pos_.getZ(), movementSpeed))) {
          log("tick() -> abort, !tryMoveToXYZ()");
          abort();
        }
      } else {
        log("tick() -> abort, in position)");
        in_position_ = true;
        abort();
        if(on_target_position_reached_ != null) on_target_position_reached_.apply(this, creature.world, target_pos_);
      }
    }
  }
}
