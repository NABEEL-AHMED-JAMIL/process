INSERT INTO source_task_type (source_task_type_id, description, queue_topic_partition, service_name, task_type_status)
VALUES ('1000', '[consumer test]', 'topic=test-topic&partitions=[*]', 'Test', 'active')
ON CONFLICT (source_task_type_id) DO NOTHING;

INSERT INTO source_task_type (source_task_type_id, description, queue_topic_partition, service_name, task_type_status)
VALUES ('1001', '[only for file type (non of image)]', 'topic=scrapping-topic&partitions=[0]', 'Web Scrapping', 'active')
ON CONFLICT (source_task_type_id) DO NOTHING;

INSERT INTO source_task_type (source_task_type_id, description, queue_topic_partition, service_name, task_type_status)
VALUES ('1002', '[only html data to other format (xml|css|xlsx|json)]', 'topic=scrapping-topic&partitions=[1]', 'Data Scrapping', 'active')
ON CONFLICT (source_task_type_id) DO NOTHING;

INSERT INTO source_task_type (source_task_type_id, description, queue_topic_partition, service_name, task_type_status)
VALUES ('1003', '[only image scrapping diff type of image]', 'topic=scrapping-topic&partitions=[2]', 'Image Scraping', 'active')
ON CONFLICT (source_task_type_id) DO NOTHING;

INSERT INTO source_task_type (source_task_type_id, description, queue_topic_partition, service_name, task_type_status)
VALUES ('1004', '[only for monitoring]', 'topic=comparison-topic&partitions=[0]', 'Image Comparison', 'active')
ON CONFLICT (source_task_type_id) DO NOTHING;

INSERT INTO source_task_type (source_task_type_id, description, queue_topic_partition, service_name, task_type_status)
VALUES ('1005', '[only for monitoring]', 'topic=comparison-topic&partitions=[1]', 'Data Comparison', 'active')
ON CONFLICT (source_task_type_id) DO NOTHING;

INSERT INTO source_task_type (source_task_type_id, description, queue_topic_partition, service_name, task_type_status)
VALUES ('1006', '[only for monitoring]', 'topic=comparison-topic&partitions=[2]', 'Web Comparison', 'active')
ON CONFLICT (source_task_type_id) DO NOTHING;

INSERT INTO source_task_type (source_task_type_id, description, queue_topic_partition, service_name, task_type_status)
VALUES ('1007', '[user uplaod file xlsx|css and extract the data as per the requirement and reportment (json|xlsx|css|xlsx)]', 'extraction-topic&partitions=[*]', 'Data Extraction', 'active')
ON CONFLICT (source_task_type_id) DO NOTHING;

INSERT INTO source_task_type (source_task_type_id, description, queue_topic_partition, service_name, task_type_status)
VALUES ('1008', '[Boots for web base automation]', 'Web Auto Bots', 'Web Auto Bots', 'active')
ON CONFLICT (source_task_type_id) DO NOTHING;

INSERT INTO source_task_type (source_task_type_id, description, queue_topic_partition, service_name, task_type_status)
VALUES ('1009', '[Boots for Mobile automation]', 'Mobile Auto Bots', 'Mobile Auto Bots', 'active')
ON CONFLICT (source_task_type_id) DO NOTHING;

INSERT INTO source_task_type (source_task_type_id, description, queue_topic_partition, service_name, task_type_status)
VALUES ('1010', '[Data|1st Dimension Statistics | 2nd Dimension Statistics]', 'Data Statistics Report', 'Data Statistics Report', 'active')
ON CONFLICT (source_task_type_id) DO NOTHING;
