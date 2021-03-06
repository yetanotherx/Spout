package org.getspout.server;

import org.getspout.api.geo.World;
import org.getspout.api.geo.cuboid.Chunk;
import org.getspout.api.material.BlockMaterial;
import org.getspout.api.material.MaterialData;
import org.getspout.api.player.Player;
import org.getspout.api.util.cuboid.CuboidShortBuffer;
import org.getspout.server.util.thread.snapshotable.SnapshotManager;
import org.getspout.server.util.thread.snapshotable.SnapshotableByteArray;
import org.getspout.server.util.thread.snapshotable.SnapshotableShortArray;

public class SpoutChunk extends Chunk {

	/**
	 * Internal representation of block ids.
	 */
	private final SnapshotableShortArray blockIds;

	/**
	 * Internal representation of block data.
	 */
	private final SnapshotableByteArray blockData;

	/**
	 * The snapshot manager for the region that this chunk is located in.
	 */
	private final SnapshotManager snapshotManager = new SnapshotManager();

	public SpoutChunk(World world, float x, float y, float z) {
		super(world, x, y, z);
		this.blockIds = new SnapshotableShortArray(snapshotManager, new short[Chunk.CHUNK_SIZE * Chunk.CHUNK_SIZE * Chunk.CHUNK_SIZE]);
		this.blockData = new SnapshotableByteArray(snapshotManager, new byte[Chunk.CHUNK_SIZE * Chunk.CHUNK_SIZE * Chunk.CHUNK_SIZE]);
	}

	public SpoutChunk(World world, float x, float y, float z, short[] blockIds, byte[] data) {
		super(world, x, y, z);
		this.blockIds = new SnapshotableShortArray(snapshotManager, blockIds);
		this.blockData = new SnapshotableByteArray(snapshotManager, data);
	}

	@Override
	public BlockMaterial setBlockMaterial(int x, int y, int z, BlockMaterial material) {
		setBlockId(x, y, z, (short) material.getRawId());
		return getBlockMaterial(x, y, z);
	}

	@Override
	public short setBlockId(int x, int y, int z, short id) {
		return blockIds.set((x & 0xF) << 8 | (z & 0xF) << 4 | y & 0xF, id);
	}

	@Override
	public BlockMaterial getBlockMaterial(int x, int y, int z) {
		short id = getBlockId(x, y, z);
		byte data = getBlockData(x, y, z);
		return MaterialData.getBlock(id, data);
	}

	@Override
	public BlockMaterial getBlockMaterial(int x, int y, int z, boolean live) {
		short id = getBlockId(x, y, z, live);
		byte data = getBlockData(x, y, z, live);
		return MaterialData.getBlock(id, data);
	}

	@Override
	public short getBlockId(int x, int y, int z) {
		return blockIds.get((x & 0xF) << 8 | (z & 0xF) << 4 | y & 0xF);
	}

	@Override
	public short getBlockId(int x, int y, int z, boolean live) {
		return live ? blockIds.getLive((x & 0xF) << 8 | (z & 0xF) << 4 | y & 0xF) : blockIds.get((x & 0xF) << 8 | (z & 0xF) << 4 | y & 0xF);
	}

	@Override
	public byte getBlockData(int x, int y, int z) {
		return blockData.get((x & 0xF) << 8 | (z & 0xF) << 4 | y & 0xF);
	}

	@Override
	public byte getBlockData(int x, int y, int z, boolean live) {
		return live ? blockData.getLive((x & 0xF) << 8 | (z & 0xF) << 4 | y & 0xF) : blockData.get((x & 0xF) << 8 | (z & 0xF) << 4 | y & 0xF);
	}

	@Override
	public byte setBlockData(int x, int y, int z, byte data) {
		return blockData.set((x & 0xF) << 8 | (z & 0xF) << 4 | y & 0xF, data);
	}

	@Override
	public void unload(boolean save) {
		// TODO Auto-generated method stub

	}

	public void copySnapshotRun() throws InterruptedException {
		snapshotManager.copyAllSnapshots();
	}

	// Saves the chunk data - this occurs directly after a snapshot update
	public void syncSave() {
		// TODO
	}
	
	// TODO - use CuboidBuffer internally ?
	public CuboidShortBuffer getBlockCuboidBufferLive() {
		int x = getX() << Chunk.CHUNK_SIZE_BITS;;
		int y = getY() << Chunk.CHUNK_SIZE_BITS;;
		int z = getZ() << Chunk.CHUNK_SIZE_BITS;
		CuboidShortBuffer snapshot = new CuboidShortBuffer(getWorld(), x, y, z, Chunk.CHUNK_SIZE, Chunk.CHUNK_SIZE, Chunk.CHUNK_SIZE);
		
		if (y < 0) {
			snapshot.flood((short)1);
		}
		
		return snapshot;
	}

	@Override
	public boolean addObserver(Player player) {
		return true;
	}

	@Override
	public boolean removeObserver(Player player) {
		return false;
	}

}
