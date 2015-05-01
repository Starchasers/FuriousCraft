package pl.starchasers.furiouscraft;

import cpw.mods.fml.common.registry.GameRegistry;
import gnu.trove.map.hash.TLongIntHashMap;
import gnu.trove.map.hash.TLongObjectHashMap;
import gnu.trove.set.hash.TLongHashSet;
import net.minecraft.crash.CrashReport;
import net.minecraft.crash.CrashReportCategory;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.IProgressUpdate;
import net.minecraft.util.ReportedException;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.MinecraftException;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.chunk.storage.IChunkLoader;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.chunkio.ChunkIOExecutor;
import net.minecraftforge.event.world.ChunkEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ChunkProviderServerFurious extends ChunkProviderServer {
	private static final Logger logger = LogManager.getLogger();
	/**
	 * used by unload100OldestChunks to iterate the loadedChunkHashMap for unload (underlying assumption, first in,
	 * first out)
	 */
	private Chunk defaultEmptyChunk;
	/** if this is false, the defaultEmptyChunk will be returned by the provider */
	public TLongObjectHashMap<Chunk> loadedChunkHashMap = new TLongObjectHashMap<Chunk>();
	public TLongHashSet chunksToForce = new TLongHashSet();
	public TLongObjectHashMap<Chunk> chunksToSave = new TLongObjectHashMap<Chunk>();
	public TLongIntHashMap chunkGracePeriod = new TLongIntHashMap();
	public TLongIntHashMap chunkMaxGracePeriod = new TLongIntHashMap();
	public TLongHashSet chunkNeedsSaving = new TLongHashSet();
	private Set<Long> loadingChunks = com.google.common.collect.Sets.newHashSet();
	private boolean isSaving = false;
	private long time;

	public ChunkProviderServerFurious(WorldServer p_i1520_1_, IChunkLoader p_i1520_2_, IChunkProvider p_i1520_3_)
	{
		super(p_i1520_1_, p_i1520_2_, p_i1520_3_);
		this.defaultEmptyChunk = new EmptyChunk(p_i1520_1_, 0, 0);
	}

	/**
	 * Checks to see if a chunk exists at x, y
	 */
	public boolean chunkExists(int p_73149_1_, int p_73149_2_)
	{
		return this.loadedChunkHashMap.containsKey(ChunkCoordIntPair.chunkXZ2Int(p_73149_1_, p_73149_2_));
	}

	public List func_152380_a() {
		return new ArrayList(this.loadedChunkHashMap.valueCollection());
	}

	/**
	 * marks chunk for unload by "unload100OldestChunks"  if there is no spawn point, or if the center of the chunk is
	 * outside 200 blocks (x or z) of the spawn
	 */
	public void unloadChunksIfNotNearSpawn(int p_73241_1_, int p_73241_2_)
	{
		if (this.worldObj.provider.canRespawnHere() && DimensionManager.shouldLoadSpawn(this.worldObj.provider.dimensionId))
		{
			ChunkCoordinates chunkcoordinates = this.worldObj.getSpawnPoint();
			int k = p_73241_1_ * 16 + 8 - chunkcoordinates.posX;
			int l = p_73241_2_ * 16 + 8 - chunkcoordinates.posZ;
			short short1 = 128;

			if (k < -short1 || k > short1 || l < -short1 || l > short1)
			{
				this.chunksToForce.remove(Long.valueOf(ChunkCoordIntPair.chunkXZ2Int(p_73241_1_, p_73241_2_)));
			}
		}
		else
		{
			this.chunksToForce.remove(Long.valueOf(ChunkCoordIntPair.chunkXZ2Int(p_73241_1_, p_73241_2_)));
		}
	}

	/**
	 * marks all chunks for unload, ignoring those near the spawn
	 */
	public void unloadAllChunks()
	{
		for (Chunk chunk : this.loadedChunkHashMap.valueCollection()) {
			this.unloadChunksIfNotNearSpawn(chunk.xPosition, chunk.zPosition);
		}
	}

	/**
	 * loads or generates the chunk at the chunk location specified
	 */
	public Chunk loadChunk(int p_73158_1_, int p_73158_2_)
	{
		return loadChunk(p_73158_1_, p_73158_2_, null);
	}

	public Chunk loadChunk(int par1, int par2, Runnable runnable)
	{
		long k = ChunkCoordIntPair.chunkXZ2Int(par1, par2);
		synchronized (this.chunksToSave) {
			if (this.chunksToSave.containsKey(k)) {
				finishSavingChunk(k, chunksToSave.get(k));
			}
		}
		Chunk chunk = loadedChunkHashMap.get(k);
		AnvilChunkLoader loader = null;

		if (this.currentChunkLoader instanceof AnvilChunkLoader)
		{
			loader = (AnvilChunkLoader) this.currentChunkLoader;
		}

		// We can only use the queue for already generated chunks
		if (chunk == null && loader != null && loader.chunkExists(this.worldObj, par1, par2))
		{
			if (runnable != null)
			{
				ChunkIOExecutor.queueChunkLoad(this.worldObj, loader, this, par1, par2, runnable);
				return null;
			}
			else
			{
				chunk = ChunkIOExecutor.syncChunkLoad(this.worldObj, loader, this, par1, par2);
			}
		}
		else if (chunk == null)
		{
			chunk = this.originalLoadChunk(par1, par2);
		}

		// If we didn't load the chunk async and have a callback run it now
		if (runnable != null)
		{
			runnable.run();
		}

		return chunk;
	}

	public Chunk originalLoadChunk(int p_73158_1_, int p_73158_2_)
	{
		long k = ChunkCoordIntPair.chunkXZ2Int(p_73158_1_, p_73158_2_);
		this.chunksToForce.add(k);
		synchronized (this.chunksToSave) {
			if (this.chunksToSave.containsKey(k)) {
				finishSavingChunk(k, this.chunksToSave.get(k));
			}
		}
		Chunk chunk = this.loadedChunkHashMap.get(k);

		if (chunk == null)
		{
			boolean added = loadingChunks.add(k);
			if (!added)
			{
				cpw.mods.fml.common.FMLLog.bigWarning("There is an attempt to load a chunk (%d,%d) in dimension %d that is already being loaded. This will cause weird chunk breakages.", p_73158_1_, p_73158_2_, worldObj.provider.dimensionId);
			}
			chunk = ForgeChunkManager.fetchDormantChunk(k, this.worldObj);
			if (chunk == null) {
				chunk = this.safeLoadChunk(p_73158_1_, p_73158_2_);
			}

			if (chunk == null)
			{
				if (this.currentChunkProvider == null)
				{
					chunk = this.defaultEmptyChunk;
				}
				else
				{
					try
					{
						chunk = this.currentChunkProvider.provideChunk(p_73158_1_, p_73158_2_);
					}
					catch (Throwable throwable)
					{
						CrashReport crashreport = CrashReport.makeCrashReport(throwable, "Exception generating new chunk");
						CrashReportCategory crashreportcategory = crashreport.makeCategory("Chunk to be generated");
						crashreportcategory.addCrashSection("Location", String.format("%d,%d", new Object[] {Integer.valueOf(p_73158_1_), Integer.valueOf(p_73158_2_)}));
						crashreportcategory.addCrashSection("Position hash", Long.valueOf(k));
						crashreportcategory.addCrashSection("Generator", this.currentChunkProvider.makeString());
						throw new ReportedException(crashreport);
					}
				}
			}

			synchronized (this.loadedChunkHashMap) {
				this.loadedChunkHashMap.put(k, chunk);
				loadingChunks.remove(k);
				chunk.onChunkLoad();
				chunk.populateChunk(this, this, p_73158_1_, p_73158_2_);
			}
		}

		return chunk;
	}


	private void updateLoadedChunks() {
		if (this.loadedChunks.size() > 0) {
			synchronized (this.loadedChunkHashMap) {
				for (Chunk c : (List<Chunk>) this.loadedChunks) {
					long l = ChunkCoordIntPair.chunkXZ2Int(c.xPosition, c.zPosition);
					this.loadedChunkHashMap.put(l, c);
					this.chunksToForce.add(l);
					loadingChunks.remove(l);
					super.loadedChunkHashMap.remove(l);
				}
				loadedChunks.clear();
			}
		}
	}

	/**
q	 * Will return back a chunk, if it doesn't exist and its not a MP client it will generates all the blocks for the
	 * specified chunk from the map seed and chunk seed
	 */
	public Chunk provideChunk(int p_73154_1_, int p_73154_2_)
	{
		updateLoadedChunks();
		long l = ChunkCoordIntPair.chunkXZ2Int(p_73154_1_, p_73154_2_);

		Chunk chunk = this.loadedChunkHashMap.get(l);
		if (chunk != null) {
			return chunk;
		} else {
			if (!isSaving) {
				synchronized (chunksToSave) {
					if (chunksToSave.containsKey(l)) {
						synchronized (FuriousCraftPartialTileUnloader.chunks) {
							FuriousCraftPartialTileUnloader.chunks.remove(chunk);
						}
						chunk = this.chunksToSave.get(l);
						this.loadedChunkHashMap.put(l, chunk);
						this.chunksToSave.remove(l);

						int gracePeriod = this.chunkMaxGracePeriod.containsKey(l) ? this.chunkMaxGracePeriod.get(l) : 0;
						gracePeriod = Math.max(31, ((gracePeriod + 1) * 2) - 1);
						this.chunkGracePeriod.put(l, gracePeriod);
						this.chunkMaxGracePeriod.put(l, gracePeriod);

						loadChunkPartial(chunk);
						return chunk;
					}
				}
			}

			synchronized (this.chunksToSave) {
				return !this.worldObj.findingSpawnPoint && !this.loadChunkOnProvideRequest ? this.defaultEmptyChunk : this.loadChunk(p_73154_1_, p_73154_2_);
			}
		}
	}

	private void unloadChunkPartial(Chunk chunk) {
		chunk.isChunkLoaded = false;
		synchronized (FuriousCraftPartialTileUnloader.chunks) {
			FuriousCraftPartialTileUnloader.chunks.add(chunk);
		}
	}

	private void unloadChunkFinish(Chunk chunk) {
		chunk.onChunkUnload();
	}

	private void loadChunkPartial(Chunk chunk) {
		chunk.isChunkLoaded = true;
		this.worldObj.func_147448_a(chunk.chunkTileEntityMap.values());

		for (int i = 0; i < chunk.entityLists.length; ++i)
		{
			this.worldObj.loadedEntityList.addAll(chunk.entityLists[i]);
			for (Entity e : (List<Entity>) chunk.entityLists[i]) {
				e.isDead = false;
				this.worldObj.onEntityAdded(e);
			}
		}
	}

	/**
	 * used by loadChunk, but catches any exceptions if the load fails.
	 */
	private Chunk safeLoadChunk(int p_73239_1_, int p_73239_2_)
	{
		if (this.currentChunkLoader == null)
		{
			return null;
		}
		else
		{
			try
			{
				Chunk chunk = this.currentChunkLoader.loadChunk(this.worldObj, p_73239_1_, p_73239_2_);

				if (chunk != null)
				{
					chunk.lastSaveTime = this.worldObj.getTotalWorldTime();

					if (this.currentChunkProvider != null)
					{
						this.currentChunkProvider.recreateStructures(p_73239_1_, p_73239_2_);
					}
				}

				return chunk;
			}
			catch (Exception exception)
			{
				logger.error("Couldn\'t load chunk", exception);
				return null;
			}
		}
	}

	/**
	 * used by saveChunks, but catches any exceptions if the save fails.
	 */
	private void safeSaveExtraChunkData(Chunk p_73243_1_)
	{
		if (this.currentChunkLoader != null)
		{
			try
			{
				this.currentChunkLoader.saveExtraChunkData(this.worldObj, p_73243_1_);
			}
			catch (Exception exception)
			{
				logger.error("Couldn\'t save entities", exception);
			}
		}
	}

	/**
	 * used by saveChunks, but catches any exceptions if the save fails.
	 */
	private void safeSaveChunk(Chunk p_73242_1_)
	{
		if (this.currentChunkLoader != null)
		{
			try
			{
				p_73242_1_.lastSaveTime = this.worldObj.getTotalWorldTime();
				this.currentChunkLoader.saveChunk(this.worldObj, p_73242_1_);
			}
			catch (IOException ioexception)
			{
				logger.error("Couldn\'t save chunk", ioexception);
			}
			catch (MinecraftException minecraftexception)
			{
				logger.error("Couldn\'t save chunk; already in use by another instance of Minecraft?", minecraftexception);
			}
		}
	}

	/**
	 * Populates chunk with ores etc etc
	 */
	public void populate(IChunkProvider p_73153_1_, int p_73153_2_, int p_73153_3_)
	{
		Chunk chunk = this.provideChunk(p_73153_2_, p_73153_3_);

		if (!chunk.isTerrainPopulated)
		{
			chunk.func_150809_p();

			if (this.currentChunkProvider != null)
			{
				this.currentChunkProvider.populate(p_73153_1_, p_73153_2_, p_73153_3_);
				GameRegistry.generateWorld(p_73153_2_, p_73153_3_, worldObj, currentChunkProvider, p_73153_1_);
				chunk.setChunkModified();
			}
		}
	}

	/**
	 * Two modes of operation: if passed true, save all Chunks in one go.  If passed false, save up to two chunks.
	 * Return true if all chunks have been saved.
	 */
	public boolean saveChunks(boolean p_73151_1_, IProgressUpdate p_73151_2_)
	{
		int i = 0;

		long[] chunks = this.loadedChunkHashMap.keys();

		for (long l : chunks)
		{
			Chunk chunk = this.loadedChunkHashMap.get(l);
			if (chunk == null) {
				continue;
			}

			if (p_73151_1_)
			{
				this.safeSaveExtraChunkData(chunk);
			}

			if (chunk.needsSaving(p_73151_1_))
			{
				this.safeSaveChunk(chunk);
				chunk.isModified = false;
				++i;

				if (i == 24 && !p_73151_1_)
				{
					return false;
				}
			}
		}

		chunks = this.chunksToSave.keys();

		for (long l : chunks)
		{
			Chunk chunk = this.chunksToSave.get(l);
			if (chunk == null) {
				continue;
			}

			if (p_73151_1_)
			{
				this.safeSaveExtraChunkData(chunk);
			}

			if (chunk.needsSaving(p_73151_1_))
			{
				this.safeSaveChunk(chunk);
				chunk.isModified = false;
				++i;

				if (i == 24 && !p_73151_1_)
				{
					return false;
				}
			}
		}

		return true;
	}

	/**
	 * Save extra data not associated with any Chunk.  Not saved during autosave, only during world unload.  Currently
	 * unimplemented.
	 */
	public void saveExtraData()
	{
		if (this.currentChunkLoader != null)
		{
			this.currentChunkLoader.saveExtraData();
		}
	}

	/**
	 * Unloads chunks that are marked to be unloaded. This is not guaranteed to unload every such chunk.
	 */
	public boolean unloadQueuedChunks()
	{
		if (!this.worldObj.levelSaving) {
			long currTime = (new Date()).getTime();
			if (currTime >= time + 15000) {
				int saved = 0;
				int queued = 0;
				int graced = 0;
				isSaving = true;

				long[] keys = this.chunksToSave.keys();
				for (long l : keys) {
					Chunk chunk = this.chunksToSave.get(l);
					saved++;
					finishSavingChunk(l, chunk);
				}

				if (this.loadedChunkHashMap.size() == 0 && this.chunksToSave.size() == 0 && ForgeChunkManager.getPersistentChunksFor(this.worldObj).size() == 0 && !DimensionManager.shouldLoadSpawn(this.worldObj.provider.dimensionId)) {
					DimensionManager.unloadWorld(this.worldObj.provider.dimensionId);
					return currentChunkProvider.unloadQueuedChunks();
				}

				keys = this.loadedChunkHashMap.keys();
				int totalSaved = keys.length;
				for (long l : keys) {
					if (chunksToForce.contains(l)) {
						continue;
					}
					if (this.loadChunkOnProvideRequest && chunkGracePeriod.get(l) > 0) {
						graced++;
						chunkGracePeriod.put(l, chunkGracePeriod.get(l) - 1);
						continue;
					}
					Chunk chunk = this.loadedChunkHashMap.get(l);

					if (chunk != null) {
						synchronized (chunksToSave) {
							synchronized (this.loadedChunkHashMap) {
								unloadChunkPartial(chunk);
								this.loadedChunkHashMap.remove(l);
							}
							this.chunksToSave.put(l, chunk);
							queued++;
						}
					}
				}

				if (this.currentChunkLoader != null) {
					this.currentChunkLoader.chunkTick();
				}

				System.out.println(String.format("GC TICK: %d[%d] chunks loaded (%d queued, %d saved, %d graced)", totalSaved, this.chunksToForce.size(), queued, saved, graced));
				isSaving = false;
				time = currTime;
			}
		}

		return this.currentChunkProvider.unloadQueuedChunks();
	}

	/**
	 * Returns if the IChunkProvider supports saving.
	 */
	public boolean canSave()
	{
		return !this.worldObj.levelSaving;
	}

	/**
	 * Converts the instance data to a readable string.
	 */
	public String makeString()
	{
		return "ServerChunkCache: " + this.loadedChunkHashMap.keySet().size() + " Drop: " + this.chunksToSave.size();
	}

	/**
	 * Returns a list of creatures of the specified type that can spawn at the given location.
	 */
	public List getPossibleCreatures(EnumCreatureType p_73155_1_, int p_73155_2_, int p_73155_3_, int p_73155_4_)
	{
		return this.currentChunkProvider.getPossibleCreatures(p_73155_1_, p_73155_2_, p_73155_3_, p_73155_4_);
	}

	public ChunkPosition func_147416_a(World p_147416_1_, String p_147416_2_, int p_147416_3_, int p_147416_4_, int p_147416_5_)
	{
		return this.currentChunkProvider.func_147416_a(p_147416_1_, p_147416_2_, p_147416_3_, p_147416_4_, p_147416_5_);
	}

	public int getLoadedChunkCount()
	{
		return this.loadedChunkHashMap.keySet().size();
	}

	public void recreateStructures(int p_82695_1_, int p_82695_2_) {}

	private void finishSavingChunk(long l, Chunk chunk) {
		synchronized (chunksToSave) {
			if (chunk != null) {
				unloadChunkFinish(chunk);
				this.safeSaveChunk(chunk);
				this.safeSaveExtraChunkData(chunk);
				this.chunkGracePeriod.remove(l);
				this.chunkMaxGracePeriod.remove(l);
				ForgeChunkManager.putDormantChunk(ChunkCoordIntPair.chunkXZ2Int(chunk.xPosition, chunk.zPosition), chunk);
				if (super.loadedChunkHashMap.containsItem(l)) {
					super.loadedChunks.remove(super.loadedChunkHashMap.getValueByKey(l));
					super.loadedChunkHashMap.remove(l);
				}
			}
			this.chunksToSave.remove(l);
		}
	}
}