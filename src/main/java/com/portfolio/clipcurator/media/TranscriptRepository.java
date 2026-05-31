package com.portfolio.clipcurator.media;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TranscriptRepository extends JpaRepository<Transcript, UUID> {

	@Query("select t from Transcript t join fetch t.mediaAsset where t.id in :ids")
	List<Transcript> findAllByIdIn(@Param("ids") Collection<UUID> ids);

	long deleteByMediaAsset_Id(UUID mediaAssetId);
}
