#include <sstream>

#include "gmock/gmock.h"
#include "gtest/gtest.h"

#include "androidfw/ApkAssets.h"
#include "androidfw/Idmap.h"

#include "idmap2/BinaryStreamVisitor.h"
#include "idmap2/Idmap.h"

#include "TestHelpers.h"

using ::testing::IsNull;
using ::testing::NotNull;

namespace android {
namespace idmap2 {

TEST(BinaryStreamVisitorTests, CreateBinaryStreamViaBinaryStreamVisitor) {
  std::string raw(reinterpret_cast<const char*>(idmap_raw_data), idmap_raw_data_len);
  std::istringstream raw_stream(raw);

  std::stringstream error;
  std::unique_ptr<const Idmap> idmap1 = Idmap::FromBinaryStream(raw_stream, error);
  ASSERT_THAT(idmap1, NotNull());

  std::stringstream stream;
  BinaryStreamVisitor visitor(stream);
  idmap1->accept(visitor);

  std::unique_ptr<const Idmap> idmap2 = Idmap::FromBinaryStream(stream, error);
  ASSERT_THAT(idmap2, NotNull());

  ASSERT_EQ(idmap1->GetHeader()->GetTargetCrc(), idmap2->GetHeader()->GetTargetCrc());
  ASSERT_EQ(idmap1->GetHeader()->GetTargetPath(), idmap2->GetHeader()->GetTargetPath());
  ASSERT_EQ(idmap1->GetData().size(), 1u);
  ASSERT_EQ(idmap1->GetData().size(), idmap2->GetData().size());

  const auto& data1 = idmap1->GetData()[0];
  const auto& data2 = idmap2->GetData()[0];

  ASSERT_EQ(data1->GetHeader()->GetTargetPackageId(), data2->GetHeader()->GetTargetPackageId());
  ASSERT_EQ(data1->GetResourceTypes().size(), 2u);
  ASSERT_EQ(data1->GetResourceTypes().size(), data2->GetResourceTypes().size());
  ASSERT_EQ(data1->GetResourceTypes()[0]->GetEntry(0), data2->GetResourceTypes()[0]->GetEntry(0));
  ASSERT_EQ(data1->GetResourceTypes()[0]->GetEntry(1), data2->GetResourceTypes()[0]->GetEntry(1));
  ASSERT_EQ(data1->GetResourceTypes()[0]->GetEntry(2), data2->GetResourceTypes()[0]->GetEntry(2));
  ASSERT_EQ(data1->GetResourceTypes()[1]->GetEntry(0), data2->GetResourceTypes()[1]->GetEntry(0));
  ASSERT_EQ(data1->GetResourceTypes()[1]->GetEntry(1), data2->GetResourceTypes()[1]->GetEntry(1));
  ASSERT_EQ(data1->GetResourceTypes()[1]->GetEntry(2), data2->GetResourceTypes()[1]->GetEntry(2));
}

TEST(BinaryStreamVisitorTests, CreateIdmapFromApkAssetsInteropWithLoadedIdmap) {
  const std::string target_apk_path(GetTestDataPath() + "/target/target.apk");
  std::unique_ptr<const ApkAssets> target_apk = ApkAssets::Load(target_apk_path);
  ASSERT_THAT(target_apk, NotNull());

  const std::string overlay_apk_path(GetTestDataPath() + "/overlay/overlay.apk");
  std::unique_ptr<const ApkAssets> overlay_apk = ApkAssets::Load(overlay_apk_path);
  ASSERT_THAT(overlay_apk, NotNull());

  std::stringstream error;
  std::unique_ptr<const Idmap> idmap =
      Idmap::FromApkAssets(target_apk_path, *target_apk, overlay_apk_path, *overlay_apk, error);
  ASSERT_THAT(idmap, NotNull());

  std::stringstream stream;
  BinaryStreamVisitor visitor(stream);
  idmap->accept(visitor);
  const std::string str = stream.str();
  const StringPiece data(str);
  std::unique_ptr<const LoadedIdmap> loaded_idmap = LoadedIdmap::Load(data);
  ASSERT_THAT(loaded_idmap, NotNull());
  ASSERT_EQ(loaded_idmap->TargetPackageId(), 0x7f);

  const IdmapEntry_header* header = loaded_idmap->GetEntryMapForType(0x01);
  ASSERT_THAT(header, NotNull());

  uint16_t resid;
  bool success = LoadedIdmap::Lookup(header, 0x0000, &resid);
  ASSERT_TRUE(success);
  ASSERT_EQ(resid, 0x0000);

  header = loaded_idmap->GetEntryMapForType(0x02);
  ASSERT_THAT(header, NotNull());

  success = LoadedIdmap::Lookup(header, 0x0002, &resid);
  ASSERT_FALSE(success);

  success = LoadedIdmap::Lookup(header, 0x0003, &resid);
  ASSERT_TRUE(success);
  ASSERT_EQ(resid, 0x0000);

  success = LoadedIdmap::Lookup(header, 0x0004, &resid);
  ASSERT_FALSE(success);

  success = LoadedIdmap::Lookup(header, 0x0005, &resid);
  ASSERT_TRUE(success);
  ASSERT_EQ(resid, 0x0001);

  success = LoadedIdmap::Lookup(header, 0x0006, &resid);
  ASSERT_TRUE(success);
  ASSERT_EQ(resid, 0x0002);

  success = LoadedIdmap::Lookup(header, 0x0007, &resid);
  ASSERT_FALSE(success);
}

}  // namespace idmap2
}  // namespace android
