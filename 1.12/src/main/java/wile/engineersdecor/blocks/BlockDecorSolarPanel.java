/*
 * @file BlockDecorDirected.java
 * @author Stefan Wilhelm (wile)
 * @copyright (C) 2019 Stefan Wilhelm
 * @license MIT (see https://opensource.org/licenses/MIT)
 *
 * Smaller (cutout) block with a defined facing.
 */
package wile.engineersdecor.blocks;

import net.minecraft.util.math.MathHelper;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;
import wile.engineersdecor.ModEngineersDecor;
import net.minecraft.block.properties.PropertyInteger;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.block.SoundType;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.world.World;
import net.minecraft.world.IBlockAccess;
import net.minecraft.util.EnumHand;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;


public class BlockDecorSolarPanel extends BlockDecor
{
  public static final PropertyInteger EXPOSITION = PropertyInteger.create("exposition", 0, 4);

  public BlockDecorSolarPanel(@Nonnull String registryName, long config, @Nullable Material material, float hardness, float resistance, @Nullable SoundType sound, @Nonnull AxisAlignedBB unrotatedAABB)
  { super(registryName, config, material, hardness, resistance, sound, unrotatedAABB); }

  @Override
  public boolean isOpaqueCube(IBlockState state)
  { return false; }

  @Override
  public boolean isFullCube(IBlockState state)
  { return false; }

  @Override
  public boolean isNormalCube(IBlockState state)
  { return false; }

  @Override
  public boolean canCreatureSpawn(IBlockState state, IBlockAccess world, BlockPos pos, net.minecraft.entity.EntityLiving.SpawnPlacementType type)
  { return false; }

  @Override
  public int getLightValue(IBlockState state, IBlockAccess world, BlockPos pos)
  { return 0; }

  @Override
  public BlockFaceShape getBlockFaceShape(IBlockAccess world, IBlockState state, BlockPos pos, EnumFacing face)
  { return BlockFaceShape.UNDEFINED; }

  @Override
  public IBlockState getStateFromMeta(int meta)
  { return this.getDefaultState().withProperty(EXPOSITION, (meta & 0x7)); }

  @Override
  public int getMetaFromState(IBlockState state)
  { return state.getValue(EXPOSITION); }

  @Override
  protected BlockStateContainer createBlockState()
  { return new BlockStateContainer(this, EXPOSITION); }

  @Override
  public boolean canPlaceBlockOnSide(World world, BlockPos pos, EnumFacing side)
  { return super.canPlaceBlockOnSide(world, pos, side); }

  @Override
  public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing, float hitX, float hitY, float hitZ, int meta, EntityLivingBase placer, EnumHand hand)
  { return getDefaultState().withProperty(EXPOSITION, 0); }

  @Override
  public boolean hasTileEntity(IBlockState state)
  { return true; }

  @Override
  @Nullable
  public TileEntity createTileEntity(World world, IBlockState state)
  { return new BlockDecorSolarPanel.BTileEntity(); }

  //--------------------------------------------------------------------------------------------------------------------
  // Tile entity
  //--------------------------------------------------------------------------------------------------------------------

  public static class BTileEntity extends TileEntity implements ITickable
  {
    public static final int DEFAULT_PEAK_POWER = 45;
    public static final int TICK_INTERVAL = 8;
    public static final int ACCUMULATION_INTERVAL = 4;
    private static final EnumFacing transfer_directions_[] = {EnumFacing.DOWN, EnumFacing.EAST, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.NORTH };
    private static int peak_power_per_tick_ = DEFAULT_PEAK_POWER;
    private static int max_power_storage_ = 10000;
    private int tick_timer_ = 0;
    private int recalc_timer_ = 0;
    private int accumulated_power_ = 0;

    public static void on_config(int peak_power_per_tick)
    {
      peak_power_per_tick_ = peak_power_per_tick;
      ModEngineersDecor.logger.info("Config small solar panel: Peak production:" + peak_power_per_tick_ + "/tick");
    }

    //------------------------------------------------------------------------------------------------------------------

    public BTileEntity()
    {}

    public void readnbt(NBTTagCompound nbt, boolean update_packet)
    { accumulated_power_ = nbt.getInteger("energy"); }

    protected void writenbt(NBTTagCompound nbt, boolean update_packet)
    { nbt.setInteger("energy", accumulated_power_); }

    // TileEntity ------------------------------------------------------------------------------

    @Override
    public boolean shouldRefresh(World world, BlockPos pos, IBlockState os, IBlockState ns)
    { return (os.getBlock() != ns.getBlock()) || (!(ns.getBlock() instanceof BlockDecorSolarPanel)); }

    @Override
    public void readFromNBT(NBTTagCompound nbt)
    { super.readFromNBT(nbt); readnbt(nbt, false); }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt)
    { super.writeToNBT(nbt); writenbt(nbt, false); return nbt; }

    @Override
    public void update()
    {
      if((world.isRemote) || (--tick_timer_ > 0)) return;
      tick_timer_ = TICK_INTERVAL;
      if(!world.canSeeSky(pos)) { tick_timer_ = TICK_INTERVAL * 5; return; }
      if(accumulated_power_ > 0) {
        for(int i=0; (i<transfer_directions_.length) && (accumulated_power_>0); ++i) {
          final EnumFacing f = transfer_directions_[i];
          TileEntity te = world.getTileEntity(pos.offset(f));
          if((te==null) || (!(te.hasCapability(CapabilityEnergy.ENERGY, f.getOpposite())))) continue;
          IEnergyStorage es = te.getCapability(CapabilityEnergy.ENERGY, f.getOpposite());
          if(!es.canReceive()) continue;
          accumulated_power_ = MathHelper.clamp(accumulated_power_-es.receiveEnergy(accumulated_power_, false),0, accumulated_power_);
        }
      }
      if(--recalc_timer_ > 0) return;
      recalc_timer_ = ACCUMULATION_INTERVAL + ((int)(Math.random()+.5));
      IBlockState state = world.getBlockState(pos);
      int theta = ((((int)(world.getCelestialAngleRadians(1f) * (180.0/Math.PI)))+90) % 360);
      int e = 2;
      if(theta > 340)      e = 2;
      else if(theta <  45) e = 0;
      else if(theta <  80) e = 1;
      else if(theta < 100) e = 2;
      else if(theta < 135) e = 3;
      else if(theta < 190) e = 4;
      IBlockState nstate = state.withProperty(EXPOSITION, e);
      if(nstate != state) world.setBlockState(pos, nstate, 1|2);
      double rf = Math.abs(1.0-(((double)Math.abs(MathHelper.clamp(theta, 0, 180)-90))/90));
      rf = Math.sqrt(rf) * world.getSunBrightnessFactor(1f) * ((TICK_INTERVAL*ACCUMULATION_INTERVAL)+2) * peak_power_per_tick_;
      accumulated_power_ = Math.min(accumulated_power_+(int)rf, max_power_storage_);
    }
  }
}