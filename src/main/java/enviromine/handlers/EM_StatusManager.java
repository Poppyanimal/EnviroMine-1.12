package enviromine.handlers;

import enviromine.EnviroPotion;
import enviromine.client.gui.UI_Settings;
import enviromine.client.gui.hud.items.Debug_Info;
import enviromine.core.EM_Settings;
import enviromine.core.EnviroMine;
import enviromine.network.packet.PacketEnviroMine;
import enviromine.trackers.EnviroDataTracker;
import enviromine.trackers.properties.*;
import enviromine.utils.EnviroUtils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockFlower;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentOxygen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.EnumCreatureAttribute;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.passive.EntityBat;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.MobEffects;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.potion.Potion;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.FoodStats;
import net.minecraft.util.math.MathHelper;
import net.minecraft.village.Village;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.Chunk;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import com.google.common.base.Stopwatch;

import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.network.NetworkRegistry.TargetPoint;
import net.minecraftforge.fml.common.registry.EntityRegistry;
import net.minecraftforge.common.EnumPlantType;

import org.apache.logging.log4j.Level;

public class EM_StatusManager
{
	public static HashMap<String,EnviroDataTracker> trackerList = new HashMap<String,EnviroDataTracker>();
	
	public static void addToManager(EnviroDataTracker tracker)
	{
		if(tracker.trackedEntity instanceof EntityPlayer)
		{
			trackerList.put("" + tracker.trackedEntity.getName(), tracker);
		} else
		{
			trackerList.put("" + tracker.trackedEntity.getEntityId(), tracker);
		}
	}
	
	public static void updateTracker(EnviroDataTracker tracker)
	{
		if(tracker == null)
		{
			return;
		}
		
		if(EnviroMine.proxy.isClient() && Minecraft.getMinecraft().isIntegratedServerRunning())
		{
			if(Minecraft.getMinecraft().isGamePaused() && !EnviroMine.proxy.isOpenToLAN())
			{
				return;
			}
		}
		
		tracker.updateTimer += 1;
		
		if(tracker.updateTimer >= 30)
		{
			tracker.updateData();
			
			if(!EnviroMine.proxy.isClient() || EnviroMine.proxy.isOpenToLAN())
			{
				syncMultiplayerTracker(tracker);
			}
		}
	}
	
	public static void syncMultiplayerTracker(EnviroDataTracker tracker)
	{
		if(!(tracker.trackedEntity instanceof EntityPlayer))
		{
			return;
		}
		
		tracker.fixFloatinfPointErrors(); // Shortens data as much as possible before sending
		NBTTagCompound pData = new NBTTagCompound();
		pData.setInteger("id", 0);
		pData.setString("player", tracker.trackedEntity.getName());
		pData.setFloat("airQuality", tracker.airQuality);
		pData.setFloat("bodyTemp", tracker.bodyTemp);
		pData.setFloat("hydration", tracker.hydration);
		pData.setFloat("sanity", tracker.sanity);
		pData.setFloat("airTemp", tracker.airTemp);
		
		EnviroMine.instance.network.sendToAllAround(new PacketEnviroMine(pData), new TargetPoint(tracker.trackedEntity.world.provider.getDimension(), tracker.trackedEntity.posX, tracker.trackedEntity.posY, tracker.trackedEntity.posZ, 128D));
	}
	
	public static EnviroDataTracker lookupTracker(EntityLivingBase entity)
	{
		if(entity instanceof EntityPlayer)
		{
			if(trackerList.containsKey("" + entity.getName()))
			{
				return trackerList.get("" + entity.getName());
			} else
			{
				return null;
			}
		} else
		{
			if(trackerList.containsKey("" + entity.getEntityId()))
			{
				return trackerList.get("" + entity.getEntityId());
			} else
			{
				return null;
			}
		}
	}
	
	public static EnviroDataTracker lookupTrackerFromUsername(String username)
	{
		if(trackerList.containsKey(username))
		{
			return trackerList.get(username);
		} else
		{
			return null;
		}
	}
	
	private static Stopwatch timer = Stopwatch.createUnstarted();
	
	public static float[] getSurroundingData(EntityLivingBase entityLiving, int range)
	{
		if(EnviroMine.proxy.isClient() && entityLiving.getName().equals(Minecraft.getMinecraft().player.getName()) && !timer.isRunning())
		{
			timer.start();
		}
		
		float[] data = new float[8];
		
		float sanityRate = 0F;
		float sanityStartRate = sanityRate;
		
		float quality = 0;
		double leaves = 0;
		float sBoost = 0;
		
		float dropSpeed = 0.001F;
		float riseSpeed = 0.001F;
		
		float temp = -999F;
		float cooling = 0;
		float dehydrateBonus = 0.0F;
		int animalHostility = 0;
		boolean nearLava = false;
		float dist = 0;
		float solidBlocks = 0;
		
		int i = MathHelper.floor(entityLiving.posX);
		int j = MathHelper.floor(entityLiving.posY);
		int k = MathHelper.floor(entityLiving.posZ);
		
		if(entityLiving.world == null)
		{
			return data;
		}
		
		Chunk chunk = entityLiving.world.getChunkFromBlockCoords(new BlockPos(i, j, k));
		
		if(chunk == null)
		{
			return data;
		}
		
		BiomeGenBase biome = chunk.getBiomeGenForWorldCoords(i & 15, k & 15, entityLiving.world.getWorldChunkManager());
		
		if(biome == null)
		{
			return data;
		}
		
		DimensionProperties dimensionProp = null;
		if(DimensionProperties.base.hasProperty(entityLiving.world.provider.getDimension()))
		{ 
			dimensionProp = DimensionProperties.base.getProperty(entityLiving.world.provider.getDimension());
		}
		
		
		float surBiomeTemps = 0;
		int biomeTempChecks = 0;
		
		boolean isDay = entityLiving.world.isDaytime();
		
		//Note: This is offset slightly so that heat peaks after noon.
		float scale = 1.25F; // Anything above 1 forces the maximum and minimum temperatures to plateau when they're reached
		float dayPercent = MathHelper.clamp((float)(Math.sin(Math.toRadians(((entityLiving.world.getWorldTime()%24000L)/24000D)*360F - 30F))*0.5F + 0.5F)*scale, 0F, 1F);
		
		if(!entityLiving.world.provider.hasSkyLight())
		{
			isDay = false;
		}
		
		int lightLev = 0;
		int blockLightLev = 0;
		
		if(j > 0)
		{
			if(j >= 256)
			{
				lightLev = 15;
				blockLightLev = 15;
			} else
			{
				lightLev = chunk.getLightFor(EnumSkyBlock.SKY, new BlockPos(i & 0xf, j, k & 0xf));
				blockLightLev = chunk.getLightFor(EnumSkyBlock.BLOCK, new BlockPos(i & 0xf, j, k & 0xf));
			}
		}
		
		if(!isDay && blockLightLev <= 1 && entityLiving.getActivePotionEffect(MobEffects.NIGHT_VISION) == null)
		{
			if(dimensionProp == null || !dimensionProp.override || dimensionProp.darkAffectSanity)
			{
			   sanityStartRate = -0.01F;
			   sanityRate = -0.01F;
			}
		}
		
		for(int x = -range; x <= range; x++)
		{
			for(int y = -range; y <= range; y++)
			{
				for(int z = -range; z <= range; z++)
				{
					if(y == 0)
					{
						Chunk testChunk = entityLiving.world.getChunkFromBlockCoords(new BlockPos((i + x), (j + y), (k + z)));
						BiomeGenBase checkBiome = testChunk.getBiomeGenForWorldCoords((i + x) & 15, (k + z) & 15, entityLiving.world.getWorldChunkManager());
						
						if(checkBiome != null)
						{
							BiomeProperties biomeOverride = null;
							if(BiomeProperties.base.hasProperty(checkBiome))
							{
								biomeOverride = BiomeProperties.base.getProperty(checkBiome);
							}
							
							if(biomeOverride != null && biomeOverride.biomeOveride)
							{
								surBiomeTemps += biomeOverride.ambientTemp;
							}
							else
							{
								//surBiomeTemps += EnviroUtils.getBiomeTemp(checkBiome);
								surBiomeTemps += EnviroUtils.getBiomeTemp((i + x),(j + y), (k + z), checkBiome);
							}
							
							biomeTempChecks += 1;
						}
					}
					
					if(!EM_PhysManager.blockNotSolid(entityLiving.world, x + i, y + j, z + k, false))
					{
						solidBlocks += 1;
					}
					
					dist = (float)entityLiving.getDistance(i + x, j + y, k + z);
					
					Block block = Blocks.AIR;
					int meta = 0;
					
					block = entityLiving.world.getBlock(i + x, j + y, k + z);
					
					if(block != Blocks.AIR)
					{
						meta = entityLiving.world.getBlockMetadata(i + x, j + y, k + z);
					}
					
					if(BlockProperties.base.hasProperty(block, meta))
					{
						BlockProperties blockProps = BlockProperties.base.getProperty(block, meta);
						
						if(blockProps.air > 0F)
						{
							leaves += (blockProps.air/0.1F);
						} else if(quality >= blockProps.air && blockProps.air < 0 && quality <= 0)
						{
							quality = blockProps.air;
						}
						if(blockProps.enableTemp)
						{
							if(temp <= getTempFalloff(blockProps.temp, dist, range) && blockProps.temp > 0F)
							{
								temp = getTempFalloff(blockProps.temp, dist, range);
							} else if(blockProps.temp < 0F)
							{
								cooling += getTempFalloff(-blockProps.temp, dist, range);
							}
						}
						if(sanityRate >= blockProps.sanity && blockProps.sanity < 0 && sanityRate <= 0)
						{
							sanityRate = blockProps.sanity;
						} else if(sanityRate <= blockProps.sanity && blockProps.sanity > 0F)
						{
							if(block instanceof BlockFlower)
							{
								if(isDay || !entityLiving.world.provider.hasSkyLight())
								{
									if(sBoost < blockProps.sanity)
									{
										sBoost = blockProps.sanity;
									}
								}
							} else
							{
								if(sBoost < blockProps.sanity)
								{
									sBoost = blockProps.sanity;
								}
							}
						}
					}
					
					if(block.getMaterial() == Material.LAVA)
					{
						nearLava = true;
					}
				}
			}
		}
		
		if(entityLiving instanceof EntityPlayer)
		{
			EntityPlayer player = (EntityPlayer)entityLiving;
			
			for(int slot = 0; slot < 9; slot++)
			{
				ItemStack stack = player.inventory.mainInventory[slot];
				
				if(stack == null)
				{
					continue;
				}
				
				float stackMult = 1F;
				
				if(stack.getCount() > 1)
				{
					stackMult = (stack.getCount()-1F)/63F + 1F;
				}
				
				if(ItemProperties.base.hasProperty(stack))
				{
					ItemProperties itemProps = ItemProperties.base.getProperty(stack);
	
					if(itemProps.ambAir > 0F)
					{
						leaves += (itemProps.ambAir/0.1F) * stackMult;
					} else if(quality >= itemProps.ambAir * stackMult && itemProps.ambAir < 0 && quality <= 0)
					{
						quality = itemProps.ambAir * stackMult;
					}
					if(temp <= itemProps.ambTemp * stackMult && itemProps.enableTemp && itemProps.ambTemp > 0F)
					{
						temp = itemProps.ambTemp * stackMult;
					} else if(itemProps.enableTemp && itemProps.ambTemp < 0F)
					{
						cooling += -itemProps.ambTemp * stackMult;
					}
					if(sanityRate >= itemProps.ambSanity * stackMult && itemProps.ambSanity < 0 && sanityRate <= 0)
					{
						sanityRate = itemProps.ambSanity * stackMult;
					} else if(sBoost <= itemProps.ambSanity * stackMult && itemProps.ambSanity > 0F)
					{
						if(stack.getItem() instanceof ItemBlock)
						{
							if(((ItemBlock)stack.getItem()).getBlock() instanceof BlockFlower)
							{
								if(isDay || !entityLiving.world.provider.hasSkyLight())
								{
									sBoost = itemProps.ambSanity * stackMult;
								}
							} else
							{
								sBoost = itemProps.ambSanity * stackMult;
							}
						} else
						{
							sBoost = itemProps.ambSanity * stackMult;
						}
					}
				} else if(stack.getItem() instanceof ItemBlock)
				{
					ItemBlock itemBlock = (ItemBlock)stack.getItem();
					if(itemBlock.getBlock() instanceof BlockFlower && (isDay || !entityLiving.world.provider.hasSkyLight()) && sBoost <= 0.1F)
					{
						if(((BlockFlower)itemBlock.getBlock()).getPlantType(entityLiving.world, new BlockPos(i, j, k)) == EnumPlantType.Plains)
						{
							sBoost = 0.1F;
						}
					}
				}
			}
		}
		
		if(lightLev > 1 && entityLiving.world.provider.hasSkyLight())
		{
			quality = 2F;
			sanityRate = 0.5F;
		} else if(sanityRate <= sanityStartRate && sanityRate > -0.1F && blockLightLev <= 1 && entityLiving.getActivePotionEffect(MobEffects.NIGHT_VISION) == null)
		{
			sanityRate = -0.1F;
		}
		
		if(dimensionProp != null && entityLiving.posY > dimensionProp.sealevel * 0.75 && entityLiving.world.provider.hasSkyLight())
		{
			quality = 2F;
		}
		

		float bTemp = (surBiomeTemps / biomeTempChecks);
		float highTemp = -30F; // Max temp at high altitude
		float lowTemp = 30F; // Min temp at low altitude (Geothermal Heating)
		
		if(entityLiving.world.provider.hasSkyLight())
		{
			if(entityLiving.posY < 48)
			{
				if(lowTemp - bTemp > 0)
				{
					bTemp += (lowTemp - bTemp) * (1F - (entityLiving.posY / 48F));
				}
			} else if(entityLiving.posY > 90 && entityLiving.posY < 256)
			{
				if(highTemp - bTemp < 0)
				{
					bTemp -= MathHelper.abs(highTemp - bTemp) * ((entityLiving.posY - 90F)/166F);
				}
			} else if(entityLiving.posY >= 256)
			{
				bTemp = bTemp < highTemp? bTemp : highTemp;
			}
		}
		
		bTemp -= cooling;
		
		if(entityLiving instanceof EntityPlayer)
		{
			if(((EntityPlayer)entityLiving).isPlayerSleeping())
			{
				bTemp += 10F;
			}
		}
		
		if (dimensionProp != null && dimensionProp.override && !dimensionProp.weatherAffectsTemp) 
		{

		}
		else 
		{
			if(entityLiving.world.isRaining() && biome.rainfall != 0.0F)
			{
				bTemp -= 10F;
				animalHostility = -1;
				
				if(entityLiving.world.canBlockSeeSky(new BlockPos(i, j, k)))
				{
					dropSpeed = 0.01F;
				}
			}
		
		} // Dimension Overrides End
	
		// 	Shade		
		if(!entityLiving.world.canBlockSeeSky(new BlockPos(i, j, k)) && isDay && !entityLiving.world.isRaining())
		{
			bTemp -= 2.5F;
		}
		
		if ((entityLiving.world.provider.hasSkyLight() && dimensionProp == null) || (dimensionProp != null && dimensionProp.override && dimensionProp.dayNightTemp))
		{ 
			bTemp -= (1F-dayPercent) * 10F;
			
			if(biome.rainfall <= 0F)
			{
				bTemp -= (1F-dayPercent) * 30F;
			}
		}
		
		@SuppressWarnings("unchecked")
		List<Entity> mobList = entityLiving.world.getEntitiesWithinAABBExcludingEntity(entityLiving, AxisAlignedBB.getBoundingBox(entityLiving.posX - 2, entityLiving.posY - 2, entityLiving.posZ - 2, entityLiving.posX + 3, entityLiving.posY + 3, entityLiving.posZ + 3));
		
		Iterator<Entity> iterator = mobList.iterator();
		
		float avgEntityTemp = 0.0F;
		int validEntities = 0;
		
		EnviroDataTracker tracker = lookupTracker(entityLiving);
		
		if(tracker == null)
		{
			EnviroMine.logger.log(Level.ERROR, "Tracker updating as null! Crash imminent!");
		}
		
		while(iterator.hasNext())
		{
			Entity mob = (Entity)iterator.next();
			
			if(!(mob instanceof EntityLivingBase))
			{
				continue;
			}
			
			EnviroDataTracker mobTrack = lookupTracker((EntityLivingBase)mob);
			EntityProperties livingProps = null;
			
			
			if(EntityProperties.base.hasProperty(mob))
			{
				livingProps = EntityProperties.base.getProperty(mob);
			}
			
//			if(EntityList.getEntityID(mob) > 0)
//			{
//				if(EM_Settings.livingProperties.containsKey(EntityList.getEntityID(mob)))
//				{
//					livingProps = EM_Settings.livingProperties.get(EntityList.getEntityID(mob));
//				}
//			} else if(EntityRegistry.instance().lookupModSpawn(mob.getClass(), false) != null)
//			{
//				if(EM_Settings.livingProperties.containsKey(EntityRegistry.instance().lookupModSpawn(mob.getClass(), false).getModEntityId() + 128))
//				{
//					livingProps = EM_Settings.livingProperties.get(EntityRegistry.instance().lookupModSpawn(mob.getClass(), false).getModEntityId() + 128);
//				}
//			}
			
			if(mob instanceof EntityVillager && entityLiving instanceof EntityPlayer && entityLiving.canEntityBeSeen(mob) && EM_Settings.villageAssist)
			{
				EntityVillager villager = (EntityVillager)mob;
				Village village = entityLiving.world.villageCollection.getNearestVillage(new BlockPos(MathHelper.floor(villager.posX), MathHelper.floor(villager.posY), MathHelper.floor(villager.posZ)), 32);
				
				long assistTime = villager.getEntityData().getLong("Enviro_Assist_Time");
				long worldTime = entityLiving.world.provider.getWorldTime();
				
				if(village != null && village.getReputationForPlayer(entityLiving.getName()) >= 5 && !villager.isChild() && Math.abs(worldTime - assistTime) > 24000)
				{
					if(villager.getProfession() == 2) // Priest
					{
						if(sBoost < 5F)
						{
							sBoost = 5F;
						}
						
						((EntityPlayer)entityLiving).addStat(EnviroAchievements.tradingFavours, 1);
					} else if(villager.getProfession() == 0 && isDay) // Farmer
					{
						if(tracker.hydration < 50F)
						{
							tracker.hydration = 100F;
							
							if(tracker.bodyTemp >= 38F)
							{
								tracker.bodyTemp -= 1F;
							}
							entityLiving.world.playSoundAtEntity(entityLiving, "random.drink", 1.0F, 1.0F);
							villager.playSound("mob.villager.yes", 1.0F, 1.0F);
							villager.getEntityData().setLong("Enviro_Assist_Time", worldTime);
							
							((EntityPlayer)entityLiving).addStat(EnviroAchievements.tradingFavours, 1);
						}
					} else if(villager.getProfession() == 4 && isDay) // Butcher
					{
						FoodStats food = ((EntityPlayer)entityLiving).getFoodStats();
						if(food.getFoodLevel() <= 10)
						{
							food.setFoodLevel(20);
							entityLiving.world.playSoundAtEntity(entityLiving, "random.burp", 0.5F, entityLiving.world.rand.nextFloat() * 0.1F + 0.9F);
							villager.playSound("mob.villager.yes", 1.0F, 1.0F);
							villager.getEntityData().setLong("Enviro_Assist_Time", worldTime);
							
							((EntityPlayer)entityLiving).addStat(EnviroAchievements.tradingFavours, 1);
						}
					}
				}
			}
			
			if(livingProps != null && entityLiving.canEntityBeSeen(mob))
			{
				if(sanityRate >= livingProps.ambSanity && livingProps.ambSanity < 0 && sanityRate <= 0)
				{
					sanityRate = livingProps.ambSanity;
				} else if(sanityRate <= livingProps.ambSanity && livingProps.ambSanity > 0F)
				{
					if(sBoost < livingProps.ambSanity)
					{
						sBoost = livingProps.ambSanity;
					}
				}
				
				if(livingProps.ambAir > 0F)
				{
					leaves += (livingProps.ambAir/0.1F);
				} else if(quality >= livingProps.ambAir && livingProps.ambAir < 0 && quality <= 0)
				{
					quality = livingProps.ambAir;
				}
				
				dehydrateBonus -= livingProps.ambHydration;
			}
			
//			else if(mob instanceof EntityBat && entityLiving instanceof EntityPlayer && entityLiving.canEntityBeSeen(mob))
//			{
//				if(sanityRate <= sanityStartRate && sanityRate > -0.01F)
//				{
//					sanityRate = -0.01F;
//				}
//			} else if(mob.getCommandSenderName().toLowerCase().contains("ender") && entityLiving instanceof EntityPlayer && entityLiving.canEntityBeSeen(mob))
//			{
//				if(sanityRate <= sanityStartRate && sanityRate > -0.1F)
//				{
//					sanityRate = -0.1F;
//				}
//			} else if(((EntityLivingBase)mob).getCreatureAttribute() == EnumCreatureAttribute.UNDEAD && entityLiving.canEntityBeSeen(mob))
//			{
//				if(sanityRate <= sanityStartRate && sanityRate > -0.01F)
//				{
//					sanityRate = -0.01F;
//				}
//			}
			
			if(mobTrack != null)
			{
				if(livingProps != null)
				{
					if(!livingProps.bodyTemp || !livingProps.shouldTrack)
					{
						avgEntityTemp += livingProps.ambTemp;
					} else
					{
						avgEntityTemp += mobTrack.bodyTemp;
					}
				} else
				{
					avgEntityTemp += mobTrack.bodyTemp;
				}
				validEntities += 1;
			} else
			{
				if(livingProps != null)
				{
					if(!livingProps.bodyTemp || !livingProps.shouldTrack)
					{
						avgEntityTemp += livingProps.ambTemp;
					} else
					{
						avgEntityTemp += 37F;
					}
					validEntities += 1;
				} else if(!(mob instanceof EntityMob))
				{
					avgEntityTemp += 37F;
					validEntities += 1;
				}
			}
		}
		
		if(validEntities > 0)
		{
			avgEntityTemp /= validEntities;
			
			if(bTemp < avgEntityTemp - 12F)
			{
				bTemp = (bTemp + (avgEntityTemp - 12F)) / 2;
			}
		}
		
		float fireProt = 0;
		
		{
			ItemStack helmet = entityLiving.getItemStackFromSlot(EntityEquipmentSlot.HEAD);
			ItemStack plate = entityLiving.getItemStackFromSlot(EntityEquipmentSlot.CHEST);
			ItemStack legs = entityLiving.getItemStackFromSlot(EntityEquipmentSlot.LEGS);
			ItemStack boots = entityLiving.getItemStackFromSlot(EntityEquipmentSlot.FEET);
			
			float tempMultTotal = 0F;
			float addTemp = 0F;
			
			if(helmet != null)
			{
				NBTTagList enchTags = helmet.getEnchantmentTagList();
				
				if(enchTags != null)
				{
					for(int index = 0; index < enchTags.tagCount(); index++)
					{
						int enID = ((NBTTagCompound)enchTags.getCompoundTagAt(index)).getShort("id");
						int enLV = ((NBTTagCompound)enchTags.getCompoundTagAt(index)).getShort("lvl");
						
						if(enID == EnchantmentOxygen.effectId)
						{
							leaves += 3F * enLV;
						} else if(enID == Enchantment.fireProtection.effectId)
						{
							fireProt += enLV;
						}
					}
				}
				
				if(ArmorProperties.base.hasProperty(helmet))
				{
					ArmorProperties props = ArmorProperties.base.getProperty(helmet);
					
					if(isDay)
					{
						if(entityLiving.world.canSeeSky(new BlockPos(i, j, k)) && bTemp > 0F)
						{
							tempMultTotal += (props.sunMult - 1.0F);
							addTemp += props.sunTemp;
						} else
						{
							tempMultTotal += (props.shadeMult - 1.0F);
							addTemp += props.shadeTemp;
						}
					} else
					{
						tempMultTotal += (props.nightMult - 1.0F);
						addTemp += props.nightTemp;
					}
					
					if(props.air > 0F)
					{
						leaves += (props.air/0.1F);
					} else if(quality >= props.air && props.air < 0 && quality <= 0)
					{
						quality = props.air;
					}
					
					if(sanityRate >= props.sanity && props.sanity < 0 && sanityRate <= 0)
					{
						sanityRate = props.sanity;
					} else if(sBoost <= props.sanity && props.sanity > 0F)
					{
						sBoost = props.sanity;
					}
				}
			}
			if(plate != null)
			{
				NBTTagList enchTags = plate.getEnchantmentTagList();
				
				if(enchTags != null)
				{
					for(int index = 0; index < enchTags.tagCount(); index++)
					{
						int enID = ((NBTTagCompound)enchTags.getCompoundTagAt(index)).getShort("id");
						int enLV = ((NBTTagCompound)enchTags.getCompoundTagAt(index)).getShort("lvl");
						
						if(enID == Enchantment.fireProtection.effectId)
						{
							fireProt += enLV;
						}
					}
				}
				
				if(ArmorProperties.base.hasProperty(plate))
				{
					ArmorProperties props = ArmorProperties.base.getProperty(plate);
					
					if(isDay)
					{
						if(entityLiving.world.canSeeSky(new BlockPos(i, j, k)) && bTemp > 0F)
						{
							tempMultTotal += (props.sunMult - 1.0F);
							addTemp += props.sunTemp;
						} else
						{
							tempMultTotal += (props.shadeMult - 1.0F);
							addTemp += props.shadeTemp;
						}
					} else
					{
						tempMultTotal += (props.nightMult - 1.0F);
						addTemp += props.nightTemp;
					}
					
					if((quality <= props.air && props.air > 0F) || (quality >= props.air && props.air < 0 && quality <= 0))
					{
						quality = props.air;
					}
					
					if(sanityRate >= props.sanity && props.sanity < 0 && sanityRate <= 0)
					{
						sanityRate = props.sanity;
					} else if(sBoost <= props.sanity && props.sanity > 0F)
					{
						sBoost = props.sanity;
					}
				}
			}
			if(legs != null)
			{
				NBTTagList enchTags = legs.getEnchantmentTagList();
				
				if(enchTags != null)
				{
					for(int index = 0; index < enchTags.tagCount(); index++)
					{
						int enID = ((NBTTagCompound)enchTags.getCompoundTagAt(index)).getShort("id");
						int enLV = ((NBTTagCompound)enchTags.getCompoundTagAt(index)).getShort("lvl");
						
						if(enID == Enchantment.fireProtection.effectId)
						{
							fireProt += enLV;
						}
					}
				}
				
				if(ArmorProperties.base.hasProperty(legs))
				{
					ArmorProperties props = ArmorProperties.base.getProperty(legs);
						
					if(isDay)
					{
						if(entityLiving.world.canSeeSky(new BlockPos(i, j, k)) && bTemp > 0F)
						{
							tempMultTotal += (props.sunMult - 1.0F);
							addTemp += props.sunTemp;
						} else
						{
							tempMultTotal += (props.shadeMult - 1.0F);
							addTemp += props.shadeTemp;
						}
					} else
					{
						tempMultTotal += (props.nightMult - 1.0F);
						addTemp += props.nightTemp;
					}
					
					if((quality <= props.air && props.air > 0F) || (quality >= props.air && props.air < 0 && quality <= 0))
					{
						quality = props.air;
					}
					
					if(sanityRate >= props.sanity && props.sanity < 0 && sanityRate <= 0)
					{
						sanityRate = props.sanity;
					} else if(sBoost <= props.sanity && props.sanity > 0F)
					{
						sBoost = props.sanity;
					}
				}
			}
			if(boots != null)
			{
				NBTTagList enchTags = boots.getEnchantmentTagList();
				
				if(enchTags != null)
				{
					for(int index = 0; index < enchTags.tagCount(); index++)
					{
						int enID = ((NBTTagCompound)enchTags.getCompoundTagAt(index)).getShort("id");
						int enLV = ((NBTTagCompound)enchTags.getCompoundTagAt(index)).getShort("lvl");
						
						if(enID == Enchantment.fireProtection.effectId)
						{
							fireProt += enLV;
						}
					}
				}
				
				if(ArmorProperties.base.hasProperty(boots))
				{
					ArmorProperties props = ArmorProperties.base.getProperty(boots);
					
					if(isDay)
					{
						if(entityLiving.world.canSeeSky(new BlockPos(i, j, k)) && bTemp > 0F)
						{
							tempMultTotal += (props.sunMult - 1.0F);
							addTemp += props.sunTemp;
						} else
						{
							tempMultTotal += (props.shadeMult - 1.0F);
							addTemp += props.shadeTemp;
						}
					} else
					{
						tempMultTotal += (props.nightMult - 1.0F);
						addTemp += props.nightTemp;
					}
					
					if((quality <= props.air && props.air > 0F) || (quality >= props.air && props.air < 0 && quality <= 0))
					{
						quality = props.air;
					}
					
					if(sanityRate >= props.sanity && props.sanity < 0 && sanityRate <= 0)
					{
						sanityRate = props.sanity;
					} else if(sBoost <= props.sanity && props.sanity > 0F)
					{
						sBoost = props.sanity;
					}
				}
			}
			
			bTemp *= (1F + tempMultTotal);
			bTemp += addTemp;
			fireProt = 1F - fireProt/18F;
		}
		
		if(entityLiving.isInWater())
		{
			if(bTemp > 25F)
			{
				if(bTemp > 50F)
				{
					bTemp -= 50F;
				} else
				{
					bTemp = 25F;
				}
			}
			dropSpeed = 0.01F;
		}
		
		float tempFin = 0F;
		
		if(temp > bTemp)
		{
			tempFin = (bTemp + temp) / 2;
			if(temp > (bTemp + 5F))
			{
				riseSpeed = 0.005F;
			}
		} else
		{
			tempFin = bTemp;
		}
		
		if(entityLiving.getActivePotionEffect(MobEffects.HUNGER) != null)
		{
			dehydrateBonus += 0.1F;
		}
		
		if(nearLava)
		{
			if(riseSpeed <= 0.005F)
			{
				riseSpeed = 0.005F;
			}
			dehydrateBonus += 0.05F;
			if(animalHostility == 0)
			{
				animalHostility = 1;
			}
		}
		
		BiomeProperties biomeProp = null;
		if(BiomeProperties.base.hasProperty(biome))
		{
			 biomeProp = BiomeProperties.base.getProperty(biome);

			 if(biomeProp != null && biomeProp.biomeOveride)
				{
					dehydrateBonus += biomeProp.dehydrateRate;
					
					if(biomeProp.tempRate > 0)
					{
						riseSpeed += biomeProp.tempRate;
					} else
					{
						dropSpeed += biomeProp.tempRate;
					}
					
					sanityRate += biomeProp.sanityRate;
				}

		}

		if(biome.getIntRainfall() == 0 && isDay)
		{
			dehydrateBonus += 0.05F;
			if(animalHostility == 0)
			{
				animalHostility = 1;
			}
		}
		
		if(!entityLiving.isPotionActive(MobEffects.FIRE_RESISTANCE))
		{
			if(entityLiving.world.getBlock(i, j, k) == Blocks.LAVA || entityLiving.world.getBlock(i, j, k) == Blocks.FLOWING_LAVA)
			{
				tempFin = 200F;
				riseSpeed = 1.0F;
			} else if(entityLiving.isBurning())
			{
				if(tempFin <= 75F)
				{
					tempFin = 75F;
				}
				
				if(riseSpeed < 0.1F)
				{
					riseSpeed = 0.1F;
				}
			}
		}
		
		quality += (leaves * 0.1F);
		sanityRate += sBoost;
		
		if(quality < 0)
		{
			quality *= solidBlocks/Math.pow(range*2, 3);
		}
		
		if(entityLiving.isSprinting())
		{
			dehydrateBonus += 0.05F;
			if(riseSpeed < 0.01F)
			{
				riseSpeed = 0.01F;
			}
			tempFin += 2F;
		}
		
		if(dimensionProp != null && dimensionProp.override)
		{   
			quality = quality * (float) dimensionProp.airMulti + dimensionProp.airRate;
			riseSpeed = riseSpeed * (float) dimensionProp.tempMulti + dimensionProp.tempRate;
			dropSpeed = dropSpeed * (float) dimensionProp.tempMulti + dimensionProp.tempRate;
			sanityRate = sanityRate * (float) dimensionProp.sanityMulti + dimensionProp.sanityRate;
			dehydrateBonus = dehydrateBonus * (float) dimensionProp.hydrationMulti + dimensionProp.hydrationRate;
		}

		
		data[0] = quality * (float)EM_Settings.airMult;
		data[1] = entityLiving.isPotionActive(MobEffects.FIRE_RESISTANCE) && tempFin > 37F? 37F : (tempFin > 37F? 37F + ((tempFin-37F) * fireProt): tempFin);
		data[2] = nearLava? 1 : 0;
		data[3] = dehydrateBonus * (float)EM_Settings.hydrationMult;
		data[4] = dropSpeed * (float)EM_Settings.tempMult;
		data[5] = riseSpeed * (float)EM_Settings.tempMult * (tracker.bodyTemp < 37F? 1F : fireProt);
		data[6] = animalHostility;
		data[7] = sanityRate * (float)EM_Settings.sanityMult;
		
		if(EnviroMine.proxy.isClient() && entityLiving.getName().equals(Minecraft.getMinecraft().player.getName()) && timer.isRunning())
		{
			timer.stop();
			Debug_Info.DB_timer = timer.toString();
			timer.reset();
		}
		return data;
	}
	
	public static float getBiomeTemprature(int x, int y, BiomeGenBase biome)
	{
		float temp= 0F;
		
		
		return temp;
		
	}
	
	public static void removeTracker(EnviroDataTracker tracker)
	{
		if(trackerList.containsValue(tracker))
		{
			tracker.isDisabled = true;
			if(tracker.trackedEntity instanceof EntityPlayer)
			{
				trackerList.remove(tracker.trackedEntity.getName());
			} else
			{
				trackerList.remove("" + tracker.trackedEntity.getEntityId());
			}
		}
	}
	
	public static void saveAndRemoveTracker(EnviroDataTracker tracker)
	{
		if(trackerList.containsValue(tracker))
		{
			tracker.isDisabled = true;
			NBTTagCompound tags = tracker.trackedEntity.getEntityData();
			tags.setFloat("ENVIRO_AIR", tracker.airQuality);
			tags.setFloat("ENVIRO_HYD", tracker.hydration);
			tags.setFloat("ENVIRO_TMP", tracker.bodyTemp);
			tags.setFloat("ENVIRO_SAN", tracker.sanity);
			if(tracker.trackedEntity instanceof EntityPlayer)
			{
				trackerList.remove(tracker.trackedEntity.getName());
			} else
			{
				trackerList.remove("" + tracker.trackedEntity.getEntityId());
			}
		}
	}
	
	public static void saveTracker(EnviroDataTracker tracker)
	{
		NBTTagCompound tags = tracker.trackedEntity.getEntityData();
		tags.setFloat("ENVIRO_AIR", tracker.airQuality);
		tags.setFloat("ENVIRO_HYD", tracker.hydration);
		tags.setFloat("ENVIRO_TMP", tracker.bodyTemp);
		tags.setFloat("ENVIRO_SAN", tracker.sanity);
	}
	
	public static void removeAllTrackers()
	{
		Iterator<EnviroDataTracker> iterator = trackerList.values().iterator();
		while(iterator.hasNext())
		{
			EnviroDataTracker tracker = iterator.next();
			tracker.isDisabled = true;
		}
		
		trackerList.clear();
	}
	
	public static void saveAndDeleteAllTrackers()
	{
		Iterator<EnviroDataTracker> iterator = trackerList.values().iterator();
		while(iterator.hasNext())
		{
			EnviroDataTracker tracker = iterator.next();
			tracker.isDisabled = true;
			NBTTagCompound tags = tracker.trackedEntity.getEntityData();
			tags.setFloat("ENVIRO_AIR", tracker.airQuality);
			tags.setFloat("ENVIRO_HYD", tracker.hydration);
			tags.setFloat("ENVIRO_TMP", tracker.bodyTemp);
			tags.setFloat("ENVIRO_SAN", tracker.sanity);
		}
		trackerList.clear();
	}
	
	public static void saveAndDeleteWorldTrackers(World world)
	{
		HashMap<String,EnviroDataTracker> tempList = new HashMap<String,EnviroDataTracker>(trackerList);
		Iterator<EnviroDataTracker> iterator = tempList.values().iterator();
		while(iterator.hasNext())
		{
			EnviroDataTracker tracker = iterator.next();
			
			if(tracker.trackedEntity.world == world)
			{
				NBTTagCompound tags = tracker.trackedEntity.getEntityData();
				tags.setFloat("ENVIRO_AIR", tracker.airQuality);
				tags.setFloat("ENVIRO_HYD", tracker.hydration);
				tags.setFloat("ENVIRO_TMP", tracker.bodyTemp);
				tags.setFloat("ENVIRO_SAN", tracker.sanity);
				tracker.isDisabled = true;
				if(tracker.trackedEntity instanceof EntityPlayer)
				{
					trackerList.remove(tracker.trackedEntity.getName());
				} else
				{
					trackerList.remove("" + tracker.trackedEntity.getEntityId());
				}
			}
		}
	}
	
	public static void saveAllWorldTrackers(World world)
	{
		HashMap<String,EnviroDataTracker> tempList = new HashMap<String,EnviroDataTracker>(trackerList);
		Iterator<EnviroDataTracker> iterator = tempList.values().iterator();
		while(iterator.hasNext())
		{
			EnviroDataTracker tracker = iterator.next();
			
			if(tracker.trackedEntity.world == world)
			{
				NBTTagCompound tags = tracker.trackedEntity.getEntityData();
				tags.setFloat("ENVIRO_AIR", tracker.airQuality);
				tags.setFloat("ENVIRO_HYD", tracker.hydration);
				tags.setFloat("ENVIRO_TMP", tracker.bodyTemp);
				tags.setFloat("ENVIRO_SAN", tracker.sanity);
			}
		}
	}
	
	public static EntityPlayer findPlayer(String username)
	{
		World[] worlds = new World[1];
		
		if(EnviroMine.proxy.isClient())
		{
			if(Minecraft.getMinecraft().isIntegratedServerRunning())
			{
				worlds = FMLCommonHandler.instance().getMinecraftServerInstance().worldServers;
			} else
			{
				worlds[0] = Minecraft.getMinecraft().player.world;
			}
		} else
		{
			worlds = FMLCommonHandler.instance().getMinecraftServerInstance().worldServers;
		}
		
		for(int i = worlds.length - 1; i >= 0; i -= 1)
		{
			if(worlds[i] == null)
			{
				continue;
			}
			EntityPlayer player = worlds[i].getPlayerEntityByName(username);
			
			if(player != null)
			{
				if(player.isEntityAlive())
				{
					return player;
				}
			}
		}
		
		return null;
	}
	
	public static void createFX(EntityLivingBase entityLiving)
	{
		float rndX = (entityLiving.getRNG().nextFloat() * entityLiving.width * 2) - entityLiving.width;
		float rndY = entityLiving.getRNG().nextFloat() * entityLiving.height;
		float rndZ = (entityLiving.getRNG().nextFloat() * entityLiving.width * 2) - entityLiving.width;
		EnviroDataTracker tracker = lookupTracker(entityLiving);
		
		if(entityLiving instanceof EntityPlayer && !(entityLiving instanceof EntityPlayerMP))
		{
			rndY = -rndY;
		}
		
		if(tracker != null)
		{
			if(tracker.bodyTemp >= 38F && UI_Settings.sweatParticals)
			{
				entityLiving.world.spawnParticle("dripWater", entityLiving.posX + rndX, entityLiving.posY + rndY, entityLiving.posZ + rndZ, 0.0D, 0.0D, 0.0D);
			}
			
			if(tracker.trackedEntity.isPotionActive(EnviroPotion.insanity) && UI_Settings.insaneParticals)
			{
				entityLiving.world.spawnParticle("portal", entityLiving.posX + rndX, entityLiving.posY + rndY, entityLiving.posZ + rndZ, 0.0D, 0.0D, 0.0D);
			}
		}
	}
	
	public static float getTempFalloff(float temp, float dist, int range)
	{
		float maximum = (float)Math.sqrt(3*(Math.pow(range, 2)));
		
		if(dist > maximum)
		{
			return 0;
		} else
		{
			return (float)((temp/Math.pow(maximum, 2)) * -Math.pow(dist, 2) + temp);
		}
	}
}
