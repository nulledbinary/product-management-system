-- Modules + Rights catalog. The application requires these rows to function;
-- they are immutable from the UI.

SET search_path = hopedb, public;

INSERT INTO module ("Module_ID", description) VALUES
  ('Prod_Mod',   'Product Management'),
  ('Report_Mod', 'Reports'),
  ('Adm_Mod',    'Administration')
ON CONFLICT ("Module_ID") DO NOTHING;

INSERT INTO rights ("Right_ID", description, "Module_ID") VALUES
  ('PRD_ADD',  'Add Product',            'Prod_Mod'),
  ('PRD_EDIT', 'Edit Product',           'Prod_Mod'),
  ('PRD_DEL',  'Soft-Delete Product',    'Prod_Mod'),
  ('REP_001',  'Product Listing Report', 'Report_Mod'),
  ('REP_002',  'Top Selling Report',     'Report_Mod'),
  ('ADM_USER', 'Manage Users',           'Adm_Mod')
ON CONFLICT ("Right_ID") DO NOTHING;
