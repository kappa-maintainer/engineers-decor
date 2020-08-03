/*
 * @file EdSoilBlock.java
 * @author Stefan Wilhelm (wile)
 * @copyright (C) 2019 Stefan Wilhelm
 * @license MIT (see https://opensource.org/licenses/MIT)
 *
 * Block type for soils, floors, etc. Drawn out into a class
 * to enable functionality block overrides.
 */
package wile.engineersdecor.blocks;

import net.minecraft.block.*;


public class EdGroundBlock extends DecorBlock.Normal implements IDecorBlock
{
  public EdGroundBlock(long config, Block.Properties builder)
  { super(config, builder); }

}