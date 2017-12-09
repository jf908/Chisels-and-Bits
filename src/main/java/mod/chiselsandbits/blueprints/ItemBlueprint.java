package mod.chiselsandbits.blueprints;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.WeakHashMap;

import mod.chiselsandbits.client.gui.ModGuiTypes;
import mod.chiselsandbits.core.ChiselsAndBits;
import mod.chiselsandbits.core.Log;
import mod.chiselsandbits.helpers.DeprecationHelper;
import mod.chiselsandbits.helpers.ModUtil;
import mod.chiselsandbits.network.NetworkRouter;
import mod.chiselsandbits.network.packets.PacketOpenGui;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public class ItemBlueprint extends Item implements Runnable
{

	final private String NBT_SIZE_X = "xSize";
	final private String NBT_SIZE_Y = "ySize";
	final private String NBT_SIZE_Z = "zSize";

	public ItemBlueprint()
	{
		setMaxStackSize( 1 );
	}

	@Override
	public ActionResult<ItemStack> onItemRightClick(
			World worldIn,
			EntityPlayer playerIn,
			EnumHand hand )
	{
		ItemStack itemStackIn = playerIn.getHeldItem( hand );

		if ( !isWritten( itemStackIn ) )
		{
			if ( worldIn.isRemote )
			{
				NetworkRouter.instance.sendToServer( new PacketOpenGui( ModGuiTypes.Blueprint ) );
			}

			return new ActionResult<ItemStack>( EnumActionResult.SUCCESS, itemStackIn );
		}

		return new ActionResult<ItemStack>( EnumActionResult.FAIL, itemStackIn );
	}

	@Override
	public String getItemStackDisplayName(
			final ItemStack stack )
	{
		if ( isWritten( stack ) )
		{
			return DeprecationHelper.translateToLocal( "item.mod.chiselsandbits.blueprint_written.name" );
		}

		return super.getItemStackDisplayName( stack );
	}

	@Override
	public EnumActionResult onItemUse(
			EntityPlayer playerIn,
			World worldIn,
			BlockPos pos,
			EnumHand hand,
			EnumFacing facing,
			float hitX,
			float hitY,
			float hitZ )
	{
		ItemStack stack = playerIn.getHeldItem( hand );

		if ( !worldIn.isRemote )
		{
			final IBlockState state = worldIn.getBlockState( pos );
			if ( !state.getBlock().isReplaceable( worldIn, pos ) )
			{
				pos = pos.offset( facing );
			}

			final EntityBlueprint e = new EntityBlueprint( worldIn );
			e.setPosition( pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5 );
			e.setItemStack( stack.copy() );

			if ( stack.hasTagCompound() )
			{
				final NBTTagCompound tag = stack.getTagCompound();
				e.setSize( tag.getInteger( NBT_SIZE_X ), tag.getInteger( NBT_SIZE_Y ), tag.getInteger( NBT_SIZE_Z ) );
			}
			else
				e.setSize( 1, 1, 1 );

			if ( !worldIn.spawnEntityInWorld( e ) )
				return EnumActionResult.FAIL;
		}

		ModUtil.adjustStackSize( stack, -1 );
		return EnumActionResult.SUCCESS;
	}

	public boolean isWritten(
			final ItemStack stack )
	{
		if ( stack.hasTagCompound() )
		{
			return verifyDataSource( stack.getTagCompound() );
		}

		return false;
	}

	private boolean verifyDataSource(
			final NBTTagCompound tagCompound )
	{
		if ( !tagCompound.hasKey( "xSize" ) || !tagCompound.hasKey( "ySize" ) || !tagCompound.hasKey( "zSize" ) )
		{
			return false;
		}

		if ( tagCompound.hasKey( "data" ) )
		{
			return true;
		}

		if ( tagCompound.hasKey( "url" ) )
		{
			final BlueprintData data = getURLData( tagCompound.getString( "url" ) );
			if ( data != null )
			{
				return data.getState().readyOrWaiting();
			}
		}

		return false;
	}

	@Override
	public void run()
	{
		while ( true )
		{
			try
			{
				synchronized ( this )
				{
					for ( final Entry<String, BlueprintData> a : data.entrySet() )
					{
						if ( a.getValue().isExpired() )
						{
							data.remove( a.getKey() );
						}
					}
				}
				Thread.sleep( 5000 );
			}
			catch ( final InterruptedException e )
			{
				Log.logError( "Error Pruning Blueprint Data!", e );
			}
		}
	}

	@SideOnly( Side.CLIENT )
	private final Map<String, BlueprintData> data = new HashMap<String, BlueprintData>();
	private final Map<ItemStack, BlueprintData> localdata = new WeakHashMap<ItemStack, BlueprintData>();
	private Thread cleanThread;

	@SideOnly( Side.CLIENT )
	synchronized protected BlueprintData getURLData(
			final String url )
	{
		if ( data.containsKey( url ) )
		{
			return data.get( url );
		}

		if ( !url.startsWith( "file://" ) && !ChiselsAndBits.getConfig().canDownload( url ) )
		{
			return null;
		}

		if ( cleanThread != null )
		{
			cleanThread = new Thread( this );
			cleanThread.setName( "BlueprintCleanup" );
			cleanThread.start();
		}

		final BlueprintData dat = new BlueprintData( url );
		data.put( url, dat );
		return dat;
	}

	@SideOnly( Side.CLIENT )
	synchronized protected BlueprintData getItemData(
			final byte[] bs,
			final ItemStack data )
	{
		if ( localdata.containsKey( data ) )
		{
			return localdata.get( data );
		}

		final BlueprintData dat = new BlueprintData( null );
		localdata.put( data, dat );

		try
		{
			dat.loadData( bs );
		}
		catch ( final IOException e )
		{

		}

		return dat;
	}

	@SideOnly( Side.CLIENT )
	protected BlueprintData getStackData(
			final ItemStack data )
	{
		if ( data.hasTagCompound() )
		{
			final NBTTagCompound tagCompound = data.getTagCompound();

			if ( tagCompound.hasKey( "data" ) )
			{
				return getItemData( tagCompound.getByteArray( "data" ), data );
			}

			if ( tagCompound.hasKey( "url" ) )
			{
				return getURLData( tagCompound.getString( "url" ) );
			}
		}

		return null;
	}

}