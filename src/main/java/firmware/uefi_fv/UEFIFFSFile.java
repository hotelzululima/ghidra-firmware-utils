/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package firmware.uefi_fv;

import java.io.IOException;
import java.util.Formatter;
import java.util.UUID;

import firmware.common.UUIDUtils;
import ghidra.app.util.bin.BinaryReader;
import ghidra.formats.gfilesystem.FileSystemIndexHelper;
import ghidra.formats.gfilesystem.GFile;
import ghidra.util.Msg;

/**
 * Parser for UEFI Firmware File System (FFS) files, which have the following header:
 *
 *   UEFI FFS File Header
 *   +------------+------+----------------------------------------+
 *   | Type       | Size | Description                            |
 *   +------------+------+----------------------------------------+
 *   | efi_guid_t |   16 | File GUID                              |
 *   | u8         |    1 | Header Checksum                        |
 *   | u8         |    1 | File Checksum                          |
 *   | u8         |    1 | Type                                   |
 *   | u8         |    1 | Attributes                             |
 *   | u24        |    3 | Size (0xFFFFFF for FFSv3 large files)  |
 *   | u8         |    1 | State                                  |
 *   | u64        |    8 | Extended Size (FFSv3 large files only) |
 *   +------------+------+----------------------------------------+
 *
 * FFSv3 files can be identified by checking if FFS_ATTRIB_LARGE_FILE (0x01) is set in the
 * Attributes field.
 *
 * Following the header, FFS files may contain multiple sections (this does not apply for raw FFS
 * files). See FFSSection for information regarding the common FFS section header, as well as
 * UEFIFFSConstants.FileType for possible FFS file Type values.
 */
public class UEFIFFSFile implements UEFIFile {
	// Original header fields
	private UUID nameGuid;
	private byte headerChecksum;
	private byte fileChecksum;
	private byte type;
	private byte attributes;
	private long size;
	private byte state;

	private long baseIndex;
	private String uiName;

	/**
	 * Constructs a UEFIFFSFile from a specified BinaryReader and adds it to a
	 * specified FileSystemIndexHelper.
	 *
	 * @param reader the specified BinaryReader
	 * @param fsih   the specified {@link FileSystemIndexHelper} that handles files
	 * @param parent the parent directory in the specified FileSystemIndexHelper
	 */
	public UEFIFFSFile(BinaryReader reader, FileSystemIndexHelper<UEFIFile> fsih, GFile parent) throws IOException {
		baseIndex = reader.getPointerIndex();
		nameGuid = UUIDUtils.fromBinaryReader(reader);
		headerChecksum = reader.readNextByte();
		fileChecksum = reader.readNextByte();
		type = reader.readNextByte();
		attributes = reader.readNextByte();

		byte[] sizeBytes = reader.readNextByteArray(3);
		size = ((sizeBytes[2] & 0xFF) << 16 | (sizeBytes[1] & 0xFF) << 8 | sizeBytes[0] & 0xFF);
		if (size < UEFIFFSConstants.FFS_HEADER_SIZE) {
			throw new IOException("Not a valid FFS file");
		}

		state = reader.readNextByte();

		// Ignore padding files.
		if (type == UEFIFFSConstants.FileType.PAD) {
			Msg.debug(this, "Skipping padding file");
			reader.setPointerIndex(baseIndex + size);
			return;
		}

		// Ignore obviously invalid sections (free space).
		if (nameGuid.equals(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")) ||
				nameGuid.equals(UUID.fromString("00000000-0000-0000-0000-000000000000"))) {
			throw new IOException("Not a valid FFS file");
		}

		// Read the extended size for FFSv3 files.
		if ((attributes & UEFIFFSConstants.Attributes.LARGE_FILE) != 0) {
			size = reader.readNextLong();
		}

		// Check if there is a UI section containing a name for this file.
		// This has to be done before the GFile for this FFS file is created, as the GFile's name
		// cannot be changed after it is constructed.
		if (type != UEFIFFSConstants.FileType.RAW) {
			long currentIndex = reader.getPointerIndex();
			long remainingLength = size - UEFIFFSConstants.FFS_HEADER_SIZE;
			while (remainingLength > UEFIFFSConstants.FFS_SECTION_HEADER_SIZE) {
				FFSSection section = FFSSectionFactory.parseSection(reader);
				if (section.getType() == UEFIFFSConstants.SectionType.USER_INTERFACE) {
					uiName = ((FFSUISection) section).getText();
					break;
				}

				long unalignedIndex = reader.getPointerIndex();
				reader.align(4);
				long alignedIndex = reader.getPointerIndex();
				remainingLength -= section.getTotalLength() + (alignedIndex - unalignedIndex);
			}

			reader.setPointerIndex(currentIndex);
		}

		// Add this file to the current FS.
		GFile fileImpl = fsih.storeFileWithParent(UEFIFirmwareVolumeFileSystem.getFSFormattedName(this, parent, fsih),
				parent, -1, true, -1, this);

		if (type == UEFIFFSConstants.FileType.RAW) {
			// Raw sections may contain a nested firmware volume.
			long currentIndex = reader.getPointerIndex();
			try {
				new UEFIFirmwareVolumeHeader(reader, fsih, fileImpl, true);
			} catch (IOException e) {
				reader.setPointerIndex(currentIndex);
				new FFSRawFile(reader, (int) size - UEFIFFSConstants.FFS_HEADER_SIZE, fsih, fileImpl);
			}
		} else {
			long remainingLength = size - UEFIFFSConstants.FFS_HEADER_SIZE;
			// Parse and add each section to the FS.
			while (remainingLength > UEFIFFSConstants.FFS_SECTION_HEADER_SIZE) {
				FFSSection section = FFSSectionFactory.parseSection(reader, fsih, fileImpl);
				// FFS sections are aligned to a 4 byte boundary.
				long unalignedIndex = reader.getPointerIndex();
				reader.align(4);
				long alignedIndex = reader.getPointerIndex();
				remainingLength -= section.getTotalLength() + (alignedIndex - unalignedIndex);
			}
		}
	}

	/**
	 * Returns the GUID for the current FFS file.
	 *
	 * @return the GUID for the current FFS file
	 */
	public UUID getGUID() {
		return nameGuid;
	}

	/**
	 * Returns the name of the current FFS file.
	 *
	 * @return the name of the current FFS file
	 */
	@Override
	public String getName() {
		// Use the UI section text (if present).
		if (uiName != null) {
			return uiName;
		}

		// Fall back to the GUID database if the UI section isn't present.
		return UUIDUtils.getName(nameGuid);
	}

	/**
	 * Returns the length of the current FFS file.
	 *
	 * @return the length of the current FFS file
	 */
	@Override
	public long length() {
		return size;
	}

	/**
	 * Returns a string representation of the current FFS file.
	 *
	 * @return a string representation of the current FFS file
	 */
	@Override
	public String toString() {
		Formatter formatter = new Formatter();
		formatter.format("File base: 0x%X\n", baseIndex);
		formatter.format("File GUID: %s\n", nameGuid.toString());
		formatter.format("File type: %s (0x%X)\n", UEFIFFSConstants.FileType.toString(type), type);
		formatter.format("File attributes: 0x%X\n", attributes);
		formatter.format("File size: 0x%X\n", size);
		formatter.format("File state: 0x%X\n", state);
		return formatter.toString();
	}
}
