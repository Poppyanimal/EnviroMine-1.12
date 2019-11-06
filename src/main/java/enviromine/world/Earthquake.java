package enviromine.world;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.apache.logging.log4j.Level;

import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.network.NetworkRegistry.TargetPoint;
import enviromine.core.EM_Settings;
import enviromine.core.EnviroMine;
import enviromine.handlers.EM_PhysManager;
import enviromine.network.packet.PacketEnviroMine;
import net.minecraft.util.math.BlockPos;

public class Earthquake
{
	public static ArrayList<Earthquake> pendingQuakes = new ArrayList<Earthquake>();
	public static ArrayList<ClientQuake> clientQuakes = new ArrayList<ClientQuake>();
	public static int lastTickDay = 0;
	public static int tickCount = 0;
	
	public World world;
	public int posX;
	public int posZ;
	
	public int length;
	public int width;
	public float angle;
	
	public int passY = 1;
	
	ArrayList<int[]> ravineMask = new ArrayList<int[]>(); // 2D array containing x,z coordinates of blocks within the ravine
	
	public Earthquake(World world, int i, int k, int l, int w)
	{
		this.posX = i;
		this.posZ = k;
		this.length = l;
		this.width = w;
		
		if(world != null)
		{
			this.world = world;
			this.angle = MathHelper.clamp(world.rand.nextFloat() * 4F - 2F, -2F, 2F);
			this.markRavine(angle);
			pendingQuakes.add(this);
			
			if(EnviroMine.proxy.isClient())
			{
				if(!(this instanceof ClientQuake))
				{
					int size = length > width? length/2 : width/2;
					NBTTagCompound pData = new NBTTagCompound();
					pData.setInteger("id", 3);
					pData.setInteger("dimension", world.provider.getDimension());
					pData.setInteger("posX", posX);
					pData.setInteger("posZ", posZ);
					pData.setInteger("length", length);
					pData.setInteger("width", width);
					pData.setFloat("angle", angle);
					pData.setFloat("action", 0);
					pData.setFloat("height", 1);
					EnviroMine.instance.network.sendToAllAround(new PacketEnviroMine(pData), new TargetPoint(world.provider.getDimension(), posX, passY, posZ, 128 + size));
				}
			}
		}
	}
	
	public Earthquake(World world, int i, int k, int l, int w, float a, boolean save)
	{
		this.posX = i;
		this.posZ = k;
		this.length = l;
		this.width = w;
		
		if(world != null)
		{
			this.world = world;
			this.angle = MathHelper.clamp(a, -2F, 2F);
			this.markRavine(angle);
			
			if(save)
			{
				pendingQuakes.add(this);
				int size = length > width? length/2 : width/2;
				NBTTagCompound pData = new NBTTagCompound();
				pData.setInteger("id", 3);
				pData.setInteger("dimension", world.provider.getDimension());
				pData.setInteger("posX", posX);
				pData.setInteger("posZ", posZ);
				pData.setInteger("length", length);
				pData.setInteger("width", width);
				pData.setFloat("angle", angle);
				pData.setFloat("action", 0);
				pData.setFloat("height", 1);
				EnviroMine.instance.network.sendToAllAround(new PacketEnviroMine(pData), new TargetPoint(world.provider.getDimension(), posX, passY, posZ, 128 + size));
			}
		}
	}
	
	public void markRavine(float angle)
	{
		ravineMask.clear();
		
		for(int i = -length / 2; i < length / 2; i++)
		{
			int fx = MathHelper.floor(Math.abs(angle) > 1F ? i * (angle > 0 ? angle - 1F : angle + 1F) : i);
			int fz = MathHelper.floor(Math.abs(angle) > 1F ? i : i * angle);
			int widthFactor = MathHelper.ceil(Math.cos(i / (length / 3D)) * width);
			
			if(Math.abs(angle) <= 1F)
			{
				for(int z = fz - widthFactor / 2; z < fz + widthFactor / 2; z++)
				{
					this.ravineMask.add(new int[]{fx + posX, 1, z + posZ});
				}
			} else
			{
				for(int x = fx - widthFactor / 2; x < fx + widthFactor / 2; x++)
				{
					this.ravineMask.add(new int[]{x + posX, 1, fz + posZ});
				}
			}
		}
		
		this.reOrderFromCenter();
	}
	
	public void reOrderFromCenter()
	{
		for(int i = 1; i < ravineMask.size(); i++)
		{
			int[] iEntry = ravineMask.get(i);
			double iDist = trigDist(iEntry[0], iEntry[2]);
			
			for(int j = i - 1; j >= 0; j--)
			{
				int[] jEntry = ravineMask.get(j);
				double jDist = trigDist(jEntry[0], jEntry[2]);
				
				if(jDist > iDist)
				{
					if(j == 0)
					{
						ravineMask.remove(i);
						ravineMask.add(j, iEntry);
					}
					continue;
				} else
				{
					if(j + 1 != i)
					{
						ravineMask.remove(i);
						ravineMask.add(j + 1, iEntry);
					}
					break;
				}
			}
		}
	}
	
	public double trigDist(double a, double b)
	{
		return (double)MathHelper.sqrt(Math.pow(a - posX, 2) + Math.pow(b - posZ, 2));
	}
	
	public boolean removeBlock()
	{
		while(passY < 256)
		{
			for(int i = 0; i < ravineMask.size(); i++)
			{
				int[] pos = this.ravineMask.get(i);
				
				BlockPos xyz = new BlockPos(pos[0], pos[1], pos[2]);
				
				boolean removed = false;
				
				if(xyz.getY() > passY)
				{
					continue;
				}
				
				for(int yy = xyz.getY(); yy >= 1; yy--)
				{
					BlockPos xyyz = new BlockPos(pos[0], yy, pos[2]);
					if((world.getBlockState(xyyz).getMaterial() == Material.LAVA && yy > 10) || world.getBlockState(xyyz).getMaterial() == Material.WATER || world.getBlockState(xyyz).getMaterial() == Material.ROCK || world.getBlockState(xyyz).getMaterial() == Material.CLAY || world.getBlockState(xyyz).getMaterial() == Material.SAND || world.getBlockState(xyyz).getMaterial() == Material.GROUND || world.getBlockState(xyyz).getMaterial() == Material.GRASS || (yy <= 10 && world.getBlockState(xyyz).getMaterial() == Material.AIR))
					{
						if(world.getBlockState(xyyz).getBlockHardness(world, xyyz) < 0)
						{
							continue;
						}
						
						if(yy <= 10)
						{
							if(world.getBlockState(xyyz).getMaterial() == Material.ROCK || world.getBlockState(xyyz).getMaterial() == Material.GROUND)
							{
								//world.playSoundEffect(x, yy, z, "enviromine:cave_in", 1.0F, world.rand.nextFloat() * 0.5F + 0.75F);
							}
							world.setBlockState(xyyz, Blocks.FLOWING_LAVA.getDefaultState());
							//System.out.println("Placed lava at (" + x + "," + yy + "," + z + ")");
							
							if(yy == xyz.getY())
							{
								if(EM_Settings.enablePhysics && EM_Settings.quakePhysics)
								{
									EM_PhysManager.schedulePhysUpdate(world, xyz.getX(), yy, xyz.getZ(), false, "Quake");
								}
								
								ravineMask.set(i, new int[]{xyz.getX(), xyz.getY() + 8, xyz.getZ()});
								removed =  true;
							}
						} else
						{
							if(world.getBlockState(xyyz).getMaterial() == Material.ROCK || world.getBlockState(xyyz).getMaterial() == Material.GROUND)
							{
								//world.playSoundEffect(x, yy, z, "enviromine:cave_in", 1.0F, world.rand.nextFloat() * 0.5F + 0.75F);
							}
							world.setBlockToAir(xyyz);
							//System.out.println("Placed air at (" + x + "," + yy + "," + z + ")");
							
							if(yy == xyz.getY())
							{
								if(EM_Settings.enablePhysics && EM_Settings.quakePhysics)
								{
									EM_PhysManager.schedulePhysUpdate(world, xyz.getX(), yy, xyz.getZ(), false, "Quake");
								}
								
								ravineMask.set(i, new int[]{xyz.getX(), xyz.getY() + 8, xyz.getZ()});
								removed =  true;
							}
						}
					}
				}
				
				if(removed)
				{
					return true;
				}
				
				Chunk chunk = world.getChunkFromBlockCoords(xyz);
				
				if((chunk != null && chunk.getLightFor(EnumSkyBlock.SKY, new BlockPos(xyz.getX() & 0xf, xyz.getY(), xyz.getZ() & 0xf)) >= 15) || world.getTopSolidOrLiquidBlock(xyz).getY() < 16 || world.canBlockSeeSky(xyz))
				{
					ravineMask.remove(i);
				} else
				{
					ravineMask.set(i, new int[]{xyz.getX(), xyz.getY() + 8, xyz.getZ()});
				}
			}
			
			passY += 8;
			int size = length > width? length/2 : width/2;
			NBTTagCompound pData = new NBTTagCompound();
			pData.setInteger("id", 3);
			pData.setInteger("dimension", world.provider.getDimension());
			pData.setInteger("posX", posX);
			pData.setInteger("posZ", posZ);
			pData.setInteger("length", length);
			pData.setInteger("width", width);
			pData.setFloat("angle", angle);
			pData.setFloat("action", 1);
			pData.setFloat("height", passY);
			EnviroMine.instance.network.sendToAllAround(new PacketEnviroMine(pData), new TargetPoint(world.provider.getDimension(), posX, passY, posZ, 128 + size));
		}
		
		return false;
	}
	
	public void removeAll()
	{
		for(int y = 1; y < world.getActualHeight(); y++)
		{
			for(int i = 0; i < this.ravineMask.size(); i++)
			{
				int[] pos = this.ravineMask.get(i);
				
				BlockPos xyz = new BlockPos(pos[0], y, pos[2]);
				
				if((world.getBlockState(xyz).getMaterial() == Material.LAVA && y > 10) || world.getBlockState(xyz).getMaterial() == Material.WATER || world.getBlockState(xyz).getMaterial() == Material.ROCK || world.getBlockState(xyz).getMaterial() == Material.CLAY || world.getBlockState(xyz).getMaterial() == Material.SAND || world.getBlockState(xyz).getMaterial() == Material.GROUND || world.getBlockState(xyz).getMaterial() == Material.GRASS || (y <= 10 && world.getBlockState(xyz).getMaterial() == Material.AIR))
				{
					if(world.getBlockState(xyz).getBlockHardness(world, xyz) < 0)
					{
						continue;
					}
					
					if(y <= 10)
					{
						world.setBlockState(xyz, Blocks.FLOWING_LAVA.getDefaultState());
					} else
					{
						world.setBlockToAir(xyz);
					}
				}
			}
		}
		
		this.ravineMask.clear();
	}
	
	public static void updateEarthquakes()
	{
		if(!EM_Settings.enableQuakes)
		{
			pendingQuakes.clear();
			return;
		}
		
		if(tickCount >= 2 * pendingQuakes.size())
		{
			tickCount = 0;
		} else
		{
			tickCount++;
			return;
		}
		
		for(int i = pendingQuakes.size() - 1; i >= 0; i--)
		{
			Earthquake quake = pendingQuakes.get(i);
			
			if(quake.world.isRemote)
			{
				pendingQuakes.remove(i);
				continue;
			}
			
			//quake.removeAll();
			if(!quake.removeBlock() || quake.ravineMask.size() <= 0)
			{
				int size = quake.length > quake.width? quake.length/2 : quake.width/2;
				NBTTagCompound pData = new NBTTagCompound();
				pData.setInteger("id", 3);
				pData.setInteger("dimension", quake.world.provider.getDimension());
				pData.setInteger("posX", quake.posX);
				pData.setInteger("posZ", quake.posZ);
				pData.setInteger("length", quake.length);
				pData.setInteger("width", quake.width);
				pData.setFloat("angle", quake.angle);
				pData.setFloat("action", 2);
				pData.setFloat("height", quake.passY);
				EnviroMine.instance.network.sendToAllAround(new PacketEnviroMine(pData), new TargetPoint(quake.world.provider.getDimension(), quake.posX, quake.passY, quake.posZ, 128 + size));
				pendingQuakes.remove(i);
			}
		}
	}
	
	public static void TickDay(World world)
	{
		if(world.rand.nextInt(2) == 0 && world.playerEntities.size() > 0)
		{
			Entity player = (Entity)world.playerEntities.get(world.rand.nextInt(world.playerEntities.size()));
			
			int posX = MathHelper.floor(player.posX) + (world.rand.nextInt(1024) - 512);
			int posZ = MathHelper.floor(player.posZ) + (world.rand.nextInt(1024) - 512);
			
			 // Chunk check can be disabled but may cause a large amount of chunks to be generated where the earthquake passes through
			if(world.getChunkProvider().isChunkGeneratedAt(posX >> 4, posZ >> 4))
			{
				new Earthquake(world, posX, posZ, 32 + world.rand.nextInt(128-32), 4 + world.rand.nextInt(32-4));
				EnviroMine.logger.log(Level.INFO, "Earthquake at (" + posX + "," + posZ + ")");
			}
		}
	}

	public static void saveQuakes(File file)
	{
		try
		{
			if(!file.exists())
			{
				file.createNewFile();
			}
			
			FileOutputStream fos = new FileOutputStream(file);
			BufferedOutputStream bos = new BufferedOutputStream(fos);
			ObjectOutputStream oos = new ObjectOutputStream(bos);
			
			ArrayList<float[]> savedQuakes = new ArrayList<float[]>();
			
			for(int i = 0; i < pendingQuakes.size(); i++)
			{
				Earthquake quake = pendingQuakes.get(i);
				float[] entry = new float[7];
				entry[0] = quake.world.provider.getDimension();
				entry[1] = quake.posX;
				entry[2] = quake.posZ;
				entry[3] = quake.length;
				entry[4] = quake.width;
				entry[5] = quake.angle;
				
				savedQuakes.add(entry);
			}
			
			oos.writeObject(savedQuakes);
			
			oos.close();
			bos.close();
			fos.close();
		} catch(Exception e)
		{
			EnviroMine.logger.log(Level.ERROR, "Failed to save Earthquakes", e);
			e.printStackTrace();
		}
	}
	
	@SuppressWarnings("unchecked")
	public static void loadQuakes(File file)
	{
		if(!file.exists())
		{
			return;
		} else
		{
			try
			{
				FileInputStream fis = new FileInputStream(file);
				BufferedInputStream bis = new BufferedInputStream(fis);
				ObjectInputStream ois = new ObjectInputStream(bis);
				
				ArrayList<float[]> loadedQuakes = (ArrayList<float[]>)ois.readObject();
				
				for(int i = 0; i < loadedQuakes.size(); i++)
				{
					float[] qData = loadedQuakes.get(i);
					
					int d = (int)qData[0];
					World world = FMLCommonHandler.instance().getMinecraftServerInstance().getWorld(d);
					int x = (int)qData[1];
					int y = (int)qData[2];
					int l = (int)qData[3];
					int w = (int)qData[4];
					float a = qData[5];
					
					new Earthquake(world, x, y, l, w, a, true);
				}
				
				ois.close();
				bis.close();
				fis.close();
			} catch(Exception e)
			{
				EnviroMine.logger.log(Level.ERROR, "Failed to load Earthquakes", e);
				e.printStackTrace();
			}
		}
	}
	
	public static void Reset()
	{
		pendingQuakes.clear();
		if(EnviroMine.proxy.isClient())
		{
			clientQuakes.clear();
		}
		lastTickDay = 0;
	}
}
