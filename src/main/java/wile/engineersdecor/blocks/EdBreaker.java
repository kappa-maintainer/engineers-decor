/*
 * @file EdBreaker.java
 * @author Stefan Wilhelm (wile)
 * @copyright (C) 2020 Stefan Wilhelm
 * @license MIT (see https://opensource.org/licenses/MIT)
 *
 * Small Block Breaker
 */
package wile.engineersdecor.blocks;

import wile.engineersdecor.ModConfig;
import wile.engineersdecor.ModContent;
import wile.engineersdecor.libmc.detail.Auxiliaries;
import wile.engineersdecor.libmc.detail.Inventories;
import wile.engineersdecor.libmc.detail.Overlay;
import net.minecraft.world.World;
import net.minecraft.world.IBlockReader;
import net.minecraft.world.GameRules;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.state.BooleanProperty;
import net.minecraft.state.StateContainer;
import net.minecraft.block.Blocks;
import net.minecraft.block.SoundType;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.tileentity.ITickableTileEntity;
import net.minecraft.tileentity.TileEntityType;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.item.BlockItemUseContext;
import net.minecraft.item.ItemStack;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.*;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;
import wile.engineersdecor.libmc.detail.RfEnergy;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Random;


public class EdBreaker
{
  public static void on_config(int boost_energy_per_tick, int breaking_time_per_hardness, int min_breaking_time_ticks, boolean power_required)
  { BreakerTileEntity.on_config(boost_energy_per_tick, breaking_time_per_hardness, min_breaking_time_ticks, power_required); }

  //--------------------------------------------------------------------------------------------------------------------
  // Block
  //--------------------------------------------------------------------------------------------------------------------

  public static class BreakerBlock extends DecorBlock.HorizontalWaterLoggable implements IDecorBlock
  {
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    public BreakerBlock(long config, Block.Properties builder, final AxisAlignedBB[] unrotatedAABBs)
    { super(config, builder, unrotatedAABBs); }

    @Override
    protected void fillStateContainer(StateContainer.Builder<Block, BlockState> builder)
    { super.fillStateContainer(builder); builder.add(ACTIVE); }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockItemUseContext context)
    { return super.getStateForPlacement(context).with(ACTIVE, false); }

    @Override
    public boolean hasTileEntity(BlockState state)
    { return true; }

    @Override
    @Nullable
    public TileEntity createTileEntity(BlockState state, IBlockReader world)
    { return new BreakerTileEntity(); }

    @OnlyIn(Dist.CLIENT)
    public void animateTick(BlockState state, World world, BlockPos pos, Random rnd)
    {
      if((state.getBlock()!=this) || (!state.get(ACTIVE))) return;
      final double rv = rnd.nextDouble();
      if(rv > 0.8) return;
      final double x=0.5+pos.getX(), y=0.5+pos.getY(), z=0.5+pos.getZ();
      final double xc=0.52, xr=rnd.nextDouble()*0.4-0.2, yr=(y-0.3+rnd.nextDouble()*0.2);
      switch(state.get(HORIZONTAL_FACING)) {
        case WEST:  world.addParticle(ParticleTypes.SMOKE, x-xc, yr, z+xr, 0.0, 0.0, 0.0); break;
        case EAST:  world.addParticle(ParticleTypes.SMOKE, x+xc, yr, z+xr, 0.0, 0.0, 0.0); break;
        case NORTH: world.addParticle(ParticleTypes.SMOKE, x+xr, yr, z-xc, 0.0, 0.0, 0.0); break;
        default:    world.addParticle(ParticleTypes.SMOKE, x+xr, yr, z+xc, 0.0, 0.0, 0.0); break;
      }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void neighborChanged(BlockState state, World world, BlockPos pos, Block block, BlockPos fromPos, boolean unused)
    {
      if(!(world instanceof World) || (((World) world).isRemote)) return;
      TileEntity te = world.getTileEntity(pos);
      if(!(te instanceof BreakerTileEntity)) return;
      ((BreakerTileEntity)te).block_updated();
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean canProvidePower(BlockState state)
    { return true; }

    @Override
    @SuppressWarnings("deprecation")
    public int getWeakPower(BlockState blockState, IBlockReader blockAccess, BlockPos pos, Direction side)
    { return 0; }

    @Override
    @SuppressWarnings("deprecation")
    public int getStrongPower(BlockState blockState, IBlockReader blockAccess, BlockPos pos, Direction side)
    { return 0; }

    @Override
    @SuppressWarnings("deprecation")
    public ActionResultType onBlockActivated(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockRayTraceResult hit)
    {
      if(world.isRemote()) return ActionResultType.SUCCESS;
      TileEntity te = world.getTileEntity(pos);
      if(te instanceof BreakerTileEntity) ((BreakerTileEntity)te).state_message(player);
      return ActionResultType.CONSUME;
    }
  }

  //--------------------------------------------------------------------------------------------------------------------
  // Tile entity
  //--------------------------------------------------------------------------------------------------------------------

  public static class BreakerTileEntity extends TileEntity implements ITickableTileEntity
  {
    public static final int IDLE_TICK_INTERVAL = 40;
    public static final int TICK_INTERVAL = 5;
    public static final int BOOST_FACTOR = 8;
    public static final int DEFAULT_BOOST_ENERGY = 64;
    public static final int DEFAULT_BREAKING_RELUCTANCE = 17;
    public static final int DEFAULT_MIN_BREAKING_TIME = 15;
    public static final int MAX_BREAKING_TIME = 800;
    private static int boost_energy_consumption = DEFAULT_BOOST_ENERGY;
    private static int energy_max = 32000;
    private static int breaking_reluctance = DEFAULT_BREAKING_RELUCTANCE;
    private static int min_breaking_time = DEFAULT_MIN_BREAKING_TIME;
    private static boolean requires_power = false;
    private int tick_timer_;
    private int active_timer_;
    private int proc_time_elapsed_;
    private int time_needed_;
    private final RfEnergy.Battery battery_;
    private final LazyOptional<IEnergyStorage> energy_handler_;

    public static void on_config(int boost_energy_per_tick, int breaking_time_per_hardness, int min_breaking_time_ticks, boolean power_required)
    {
      boost_energy_consumption = TICK_INTERVAL * MathHelper.clamp(boost_energy_per_tick, 4, 4096);
      energy_max = Math.max(boost_energy_consumption * 10, 100000);
      breaking_reluctance = MathHelper.clamp(breaking_time_per_hardness, 5, 50);
      min_breaking_time = MathHelper.clamp(min_breaking_time_ticks, 10, 100);
      requires_power = power_required;
      ModConfig.log("Config block breaker: Boost energy consumption:" + (boost_energy_consumption/TICK_INTERVAL) + "rf/t, reluctance=" + breaking_reluctance + "t/hrdn, break time offset=" + min_breaking_time + "t.");
    }

    public BreakerTileEntity()
    { this(ModContent.TET_SMALL_BLOCK_BREAKER); }

    public BreakerTileEntity(TileEntityType<?> te_type)
    {
      super(te_type);
      battery_ = new RfEnergy.Battery(energy_max, boost_energy_consumption, 0);
      energy_handler_ = battery_.createEnergyHandler();
    }

    public void block_updated()
    { if(tick_timer_ > 2) tick_timer_ = 2; }

    public void readnbt(CompoundNBT nbt)
    { battery_.load(nbt); }

    private void writenbt(CompoundNBT nbt)
    { battery_.save(nbt); }

    public void state_message(PlayerEntity player)
    {
      String progress = "0";
      if((proc_time_elapsed_ > 0) && (time_needed_ > 0)) {
        progress = Integer.toString((int)MathHelper.clamp((((double)proc_time_elapsed_) / ((double)time_needed_) * 100), 0, 100));
      }
      Overlay.show(player, Auxiliaries.localizable("block.engineersdecor.small_block_breaker.status", new Object[]{battery_.getSOC(), energy_max, progress }));
    }

    // TileEntity ------------------------------------------------------------------------------

    @Override
    public void read(BlockState state, CompoundNBT nbt)
    { super.read(state, nbt); readnbt(nbt); }

    @Override
    public CompoundNBT write(CompoundNBT nbt)
    { super.write(nbt); writenbt(nbt); return nbt; }

    @Override
    public void remove()
    {
      super.remove();
      energy_handler_.invalidate();
    }

    // Capability export ----------------------------------------------------------------------------

    @Override
    public <T> LazyOptional<T> getCapability(net.minecraftforge.common.capabilities.Capability<T> capability, @Nullable Direction facing)
    {
      if(capability == CapabilityEnergy.ENERGY) return energy_handler_.cast();
      return super.getCapability(capability, facing);
    }

    // ITickable ------------------------------------------------------------------------------------

    private static HashSet<Block> blacklist = new HashSet<>();
    static {
      blacklist.add(Blocks.AIR);
      blacklist.add(Blocks.BEDROCK);
      blacklist.add(Blocks.FIRE);
      blacklist.add(Blocks.END_PORTAL);
      blacklist.add(Blocks.END_GATEWAY);
      blacklist.add(Blocks.END_PORTAL_FRAME);
      blacklist.add(Blocks.NETHER_PORTAL);
      blacklist.add(Blocks.BARRIER);
    }

    private static boolean isBreakable(BlockState state, BlockPos pos, World world)
    {
      final Block block = state.getBlock();
      if(blacklist.contains(block)) return false;
      if(state.getMaterial().isLiquid()) return false;
      if(block.isAir(state, world, pos)) return false;
      float bh = state.getBlockHardness(world, pos);
      if((bh<0) || (bh>55)) return false;
      return true;
    }

    private static void spawnBlockAsEntity(World world, BlockPos pos, ItemStack stack) {
      if(world.isRemote || stack.isEmpty() || (!world.getGameRules().getBoolean(GameRules.DO_TILE_DROPS)) || world.restoringBlockSnapshots) return;
      ItemEntity e = new ItemEntity(world,
        ((world.rand.nextFloat()*0.1)+0.5) + pos.getX(),
        ((world.rand.nextFloat()*0.1)+0.5) + pos.getY(),
        ((world.rand.nextFloat()*0.1)+0.5) + pos.getZ(),
        stack
      );
      e.setDefaultPickupDelay();
      e.setMotion((world.rand.nextFloat()*0.1-0.05), (world.rand.nextFloat()*0.1-0.03), (world.rand.nextFloat()*0.1-0.05));
      world.addEntity(e);
    }

    private static boolean canInsertInto(World world, BlockPos pos)
    {
      // Maybe make a tag for that. The question is if it is actually worth it, or if that would be only
      // tag spamming the game. So for now only FH and VH.
      final BlockState state = world.getBlockState(pos);
      return (state.getBlock() == ModContent.FACTORY_HOPPER) || (state.getBlock() == Blocks.HOPPER);
    }

    private boolean breakBlock(BlockState state, BlockPos pos, World world)
    {
      if(world.isRemote  || (!(world instanceof ServerWorld)) || world.restoringBlockSnapshots) return false; // retry next cycle
      List<ItemStack> drops;
      final Block block = state.getBlock();
      final boolean insert = canInsertInto(world, getPos().down());
      drops = Block.getDrops(state, (ServerWorld)world, pos, world.getTileEntity(pos));
      world.removeBlock(pos, false);
      for(ItemStack drop:drops) {
        if(!insert) {
          spawnBlockAsEntity(world, pos, drop);
        } else {
          final ItemStack remaining = Inventories.insert(world, getPos().down(), Direction.UP, drop, false);
          if(!remaining.isEmpty()) spawnBlockAsEntity(world, pos, remaining);
        }
      }
      SoundType stype = state.getBlock().getSoundType(state, world, pos, null);
      if(stype != null) world.playSound(null, pos, stype.getPlaceSound(), SoundCategory.BLOCKS, stype.getVolume()*0.6f, stype.getPitch());
      return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void tick()
    {
      if(--tick_timer_ > 0) return;
      final BlockState device_state = world.getBlockState(pos);
      if(!(device_state.getBlock() instanceof BreakerBlock)) return;
      if(world.isRemote) {
        if(!device_state.get(BreakerBlock.ACTIVE)) {
          tick_timer_ = TICK_INTERVAL;
        } else {
          tick_timer_ = 1;
          // not sure if is so cool to do this each tick ... may be simplified/removed again.
          SoundEvent sound = SoundEvents.BLOCK_WOOD_HIT;
          BlockState target_state = world.getBlockState(pos.offset(device_state.get(BreakerBlock.HORIZONTAL_FACING)));
          SoundType stype = target_state.getBlock().getSoundType(target_state);
          if((stype == SoundType.CLOTH) || (stype == SoundType.PLANT) || (stype == SoundType.SNOW)) {
            sound = SoundEvents.BLOCK_WOOL_HIT;
          } else if((stype == SoundType.GROUND) || (stype == SoundType.SAND)) {
            sound = SoundEvents.BLOCK_GRAVEL_HIT;
          }
          world.playSound(pos.getX(), pos.getY(), pos.getZ(), sound, SoundCategory.BLOCKS, 0.1f, 1.2f, false);
        }
      } else {
        tick_timer_ = TICK_INTERVAL;
        final BlockPos target_pos = pos.offset(device_state.get(BreakerBlock.HORIZONTAL_FACING));
        final BlockState target_state = world.getBlockState(target_pos);
        if((world.isBlockPowered(pos)) || (!isBreakable(target_state, target_pos, world))) {
          if(device_state.get(BreakerBlock.ACTIVE)) world.setBlockState(pos, device_state.with(BreakerBlock.ACTIVE, false), 1|2);
          proc_time_elapsed_ = 0;
          tick_timer_ = IDLE_TICK_INTERVAL;
          return;
        }
        time_needed_ = MathHelper.clamp((int)(target_state.getBlockHardness(world, pos) * breaking_reluctance) + min_breaking_time, min_breaking_time, MAX_BREAKING_TIME);
        if(battery_.draw(boost_energy_consumption)) {
          proc_time_elapsed_ += TICK_INTERVAL * (1+BOOST_FACTOR);
          time_needed_ += min_breaking_time * (3*BOOST_FACTOR/5);
          active_timer_ = 2;
        } else if(!requires_power) {
          proc_time_elapsed_ += TICK_INTERVAL;
          active_timer_ = 1024;
        } else if(active_timer_ > 0) {
          --active_timer_;
        }
        boolean active = (active_timer_ > 0);
        if(requires_power && !active) {
          proc_time_elapsed_ = Math.max(0, proc_time_elapsed_ - 2*TICK_INTERVAL);
        }
        if(proc_time_elapsed_ >= time_needed_) {
          proc_time_elapsed_ = 0;
          breakBlock(target_state, target_pos, world);
          active = false;
        }
        if(device_state.get(BreakerBlock.ACTIVE) != active) {
          world.setBlockState(pos, device_state.with(BreakerBlock.ACTIVE, active), 1|2);
        }
      }
    }
  }
}
