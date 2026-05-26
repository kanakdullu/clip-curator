package com.portfolio.clipcurator.media;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface VisualFrameRepository extends JpaRepository<VisualFrame, UUID> {

	@Query("select vf from VisualFrame vf join fetch vf.mediaAsset where vf.id in :ids")
	List<VisualFrame> findAllByIdIn(@Param("ids") Collection<UUID> ids);
}
